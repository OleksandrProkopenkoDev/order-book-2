package ua.spro.oderbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OderBookApplication {

  public static void main(String[] args) {
    SpringApplication.run(OderBookApplication.class, args);
  }

}
/*
* Тестовое задание:

Используя Java и Spring, создать микросервис, который будет подключаться
* к вебсокет-каналу Diff. Book Depth Streams, создавать и хранить в памяти
* локальную копию ордербука по символу BTCUSD_PERP.
* Микросервис должен иметь рест-контроллер, который должен принимать параметры
* и возвращать респонс
* аналогичный https://binance-docs.github.io/apidocs/delivery/en/#order-book.

Описание https://binance-docs.github.io/apidocs/delivery/en/#diff-book-depth-streams
* и https://binance-docs.github.io/apidocs/delivery/en/#how-to-manage-a-local-order-book-correctly*/
