package ua.spro.oderbook.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.spro.oderbook.model.OrderBook;
import ua.spro.oderbook.service.OrderBookService;

@RestController
@RequestMapping("/api")
public class OrderBookController {
  private final OrderBookService orderBookService;

  public OrderBookController(OrderBookService orderBookService) {
    this.orderBookService = orderBookService;
  }

  @GetMapping("/orderbook")
  public OrderBook getOrderBook() {
    return orderBookService.getOrderBook();
  }
}
