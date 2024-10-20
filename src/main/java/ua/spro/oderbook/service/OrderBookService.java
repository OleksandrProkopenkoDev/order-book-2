package ua.spro.oderbook.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import ua.spro.oderbook.model.OrderBook;

@Service
public class OrderBookService {
  public static final int PRICE = 0;
  public static final int QUANTITY = 1;
  public static final List<String> HTTP_ORDERS_NAMES = List.of("asks", "bids");
  public static final List<String> WEBSOCKET_ORDERS_NAMES = List.of("a", "b");
  private static final String BINANCE_API_URL =
      "https://dapi.binance.com/dapi/v1/depth?symbol=BTCUSD_PERP&limit=1000";
  private static final Logger log = LoggerFactory.getLogger(OrderBookService.class);

  private final RestTemplate restTemplate;
  private final Queue<Map<String, Object>> eventBuffer = new LinkedList<>();
  private OrderBook orderBook;
  private final Map<Double, Double> bids = new HashMap<>();
  private final Map<Double, Double> asks = new HashMap<>();
  private final ObjectMapper mapper = new ObjectMapper();

  public OrderBookService(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  @PostConstruct
  public void init() {
    log.info("Starting OrderBookService initialization...");
    initializeOrderBook();
    connectToWebSocket();
  }

  private void connectToWebSocket() {
    log.info("Connecting to WebSocket...");
    ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
    String binanceWebSocketUrl = "wss://dstream.binance.com/ws/btcusd_perp@depth@500ms";
    client
        .execute(
            URI.create(binanceWebSocketUrl),
            session ->
                session
                    .receive()
                    .doOnNext(
                        message -> {
                          // Handle the received WebSocket message here
                          String payload = message.getPayloadAsText();
                          log.info("Received WebSocket message: {}", payload);
                          try {
                            JsonNode jsonNode = mapper.readTree(payload);
                            updateOrderBookFromWebSocket(jsonNode);
                          } catch (JsonProcessingException e) {
                            log.error("Failed to parse WebSocket message: {}", payload, e);
                          }
                        })
                    .then())
        .subscribe();
  }

  @SuppressWarnings("unchecked")
  public void initializeOrderBook() {
    Map<String, Object> response = restTemplate.getForObject(BINANCE_API_URL, Map.class);
    if (response != null) {
      long lastUpdateId = Long.parseLong(response.get("lastUpdateId").toString());
      String symbol = response.get("symbol").toString();
      String pair = response.get("pair").toString();

      HTTP_ORDERS_NAMES.forEach(
          ordersName -> {
            if (response.containsKey(ordersName)) {
              ((List<List<String>>) response.get(ordersName))
                  .forEach(
                      order -> {
                        double price = Double.parseDouble(order.getFirst());
                        double quantity = Double.parseDouble(order.getLast());
                        if (ordersName.equals("bids")) {
                          bids.put(price, quantity);
                        } else if (ordersName.equals("asks")) {
                          asks.put(price, quantity);
                        }
                      });
            }
          });
      this.orderBook =
          new OrderBook(
              lastUpdateId,
              System.currentTimeMillis(),
              System.currentTimeMillis(),
              symbol,
              pair,
              mapToList(bids),
              mapToList(asks));
      log.info("OrderBook initialized : {}", orderBook);
    } else {
      log.error("OrderBook initialization failed: no response from HTTP request");
    }
    processBufferedEvents();
  }

  public OrderBook getOrderBook() {
    log.info("Fetching current order book...");
    return orderBook;
  }

  private List<List<String>> mapToList(Map<Double, Double> map) {
    return map.entrySet().stream()
        .map(entry -> List.of(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())))
        .toList();
  }

  private void processBufferedEvents() {
    log.info("Processing buffered events...");
    long lastUpdateId = orderBook.lastUpdateId();
    while (!eventBuffer.isEmpty()) {
      Map<String, Object> event = eventBuffer.poll();
      long u = Long.parseLong(event.get("u").toString());
      long U = Long.parseLong(event.get("U").toString());
      long previousUpdateId = orderBook.lastUpdateId();

      // Drop events where u is < lastUpdateId
      if (u < lastUpdateId) {
        log.info("Dropping event with u: {} as it is less than lastUpdateId: {}", U, lastUpdateId);
        continue;
      }
      // Ensure the first event has U <= lastUpdateId and u >= lastUpdateId
      if (U <= lastUpdateId && u >= lastUpdateId) {
        log.info("Processing first valid event with U: {} and u: {}", U, u);
        updateOrderBookFromEvent(event);
        this.orderBook =
            new OrderBook(
                u,
                orderBook.E(),
                orderBook.T(),
                orderBook.symbol(),
                orderBook.pair(),
                orderBook.bids(),
                orderBook.asks());
        log.info("OrderBook updated from event: {}", orderBook);
      }

      // Ensure subsequent events are sequential
      if (previousUpdateId == U) {
        log.info("Processing sequential event with U: {} and u: {}", U, u);
        updateOrderBookFromEvent(event);
        this.orderBook =
            new OrderBook(
                u,
                orderBook.E(),
                orderBook.T(),
                orderBook.symbol(),
                orderBook.pair(),
                orderBook.bids(),
                orderBook.asks());
        log.info("OrderBook updated from sequential event: {}", orderBook);
      } else {
        // if not sequential -> reinitialize orderBook
        log.warn("Events are not sequential. Reinitializing order book...");
        initializeOrderBook();
      }
    }
  }

  private void updateOrderBookFromEvent(Map<String, Object> event) {
    WEBSOCKET_ORDERS_NAMES.forEach(
        ordersName -> {
          if (event.containsKey(ordersName)) {
            ((List<List<String>>) event.get(ordersName))
                .forEach(
                    order -> {
                      double price = Double.parseDouble(order.getFirst());
                      double quantity = Double.parseDouble(order.getLast());
                      if (ordersName.equals("b")) {
                        bids.put(price, quantity);
                      } else if (ordersName.equals("a")) {
                        asks.put(price, quantity);
                      }
                    });
          }
        });
  }

  private void updateOrderBookFromWebSocket(JsonNode jsonNode) {
    log.info("Received WebSocket update event: {}", jsonNode);
    JsonNode bidsNode = jsonNode.get("b");
    JsonNode asksNode = jsonNode.get("a");

    updateOrders(bidsNode, bids);
    updateOrders(asksNode, asks);

    this.orderBook =
        new OrderBook(
            orderBook.lastUpdateId(),
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            orderBook.symbol(),
            orderBook.pair(),
            orderBook.bids(),
            orderBook.asks());
    log.info("Order book updated after websocket event: {}", orderBook);
  }

  private void updateOrders(JsonNode ordersNode, Map<Double, Double> orderMap) {
    if (ordersNode != null) {
      ordersNode.forEach(
          order -> {
            double price = order.get(PRICE).asDouble();
            double quantity = order.get(QUANTITY).asDouble();
            if (quantity == 0) {
              orderMap.remove(price);
              log.info("Removed order at price: {}", price);
            } else {
              orderMap.put(price, quantity);
              log.info("Updated order at price: {} with quantity: {} ", price, quantity);
            }
          });
    }
  }
}
/*
Example of a received WebSocket message:
{
    "e": "depthUpdate",       // Event type
    "E": 123456789,           // Event time
    "T": 123456788,           // Transaction time
    "s": "BTCUSD_PERP",       // Symbol
    "U": 157,                 // First update ID in event
    "u": 160,                 // Final update ID in event
    "b": [                    // Bids to be updated
        ["68336.6", "2345"],
        ["68335.1", "0"],     // Remove bid with price 68335.1
        ["68334.9", "25"]
    ],
    "a": [                    // Asks to be updated
        ["68336.7", "3322"],
        ["68338.0", "0"],     // Remove ask with price 68338.0
        ["68339.4", "30"]
    ]
}
*/
