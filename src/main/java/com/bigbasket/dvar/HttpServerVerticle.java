package com.bigbasket.dvar;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.Promise;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.logging.Logger;

public class HttpServerVerticle extends AbstractVerticle {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);

  @Override
  public void start(Promise<Void> promise) throws Exception {

    HttpServerOptions httpServerOptions = new HttpServerOptions();
    HttpServer server = vertx.createHttpServer(httpServerOptions);
    Router router = Router.router(vertx);
    router.get("/health-check/").handler(this::healthCheckBasic);
    router.route().handler(BodyHandler.create());
    //router.route().post().handler(this::csrfValidator);
    router.postWithRegex("/mapi/v.*/member/login").handler(this::doDispatch);
    router.postWithRegex("/mapi/v.*/member/otp/send").handler(this::doDispatch);
    router.postWithRegex("/mapi/v.*/member/update").handler(this::doDispatch);
    router.post("/test/").handler(this::test);
    //router.get().handler(this::csrfGenerator);
    server
      .requestHandler(router)
      .listen(8080, ar -> {
        if (ar.succeeded()) {
          LOGGER.info("HTTP server running on port " + "8080");
          promise.complete();
        } else {
          LOGGER.error("Could not start a HTTP server", ar.cause());
          promise.fail(ar.cause());
        }
      });
  }

  private void healthCheckBasic(RoutingContext routingContext) {
    HttpServerResponse response = routingContext.response();
    response.end("OK");
  }

  private void test(RoutingContext routingContext) {
    JsonObject resp = new JsonObject();
    resp.put("test", 1);
    routingContext.response().setStatusCode(200).end(Json.encodePrettily(resp));
  }

  private void doDispatch(RoutingContext routingContext) {
    HttpServerRequest clientRequest = routingContext.request();
    HttpClient apiClient = vertx.createHttpClient(new HttpClientOptions());
    //todo remove
    clientRequest.headers().forEach(header -> {
      LOGGER.info(String.format("%s: %s",header.getKey(), header.getValue()));
    });
    String path = routingContext.request().uri();
    LOGGER.info(path);
    path = "/test";
    HttpClientRequest apiReq = apiClient
    .request(routingContext.request().method(), config().getInteger("API_SERVER_PORT", 80), config().getString("API_SERVER_ADDRESS", "localhost"), path, apiResponse -> {
      apiResponse.bodyHandler(body -> {
        if (apiResponse.statusCode() >= 500) { // api endpoint server error, circuit breaker should fail
          LOGGER.info(String.format("%d: %s",apiResponse.statusCode(),body.toString()));
        } else {
          HttpServerResponse toRsp = routingContext.response()
            .setStatusCode(apiResponse.statusCode());
          apiResponse.headers().forEach(header -> {
            toRsp.putHeader(header.getKey(), header.getValue());
          });
          // send response
          toRsp.end(body);
        }
      });
    });
    //todo what this does
    //apiReq.setChunked(true);
    //do this for web only
    apiReq.headers().setAll(clientRequest.headers());

    // send request
    if (routingContext.getBody() == null) {
      apiReq.end();
    } else {
      apiReq.end(routingContext.getBody());
    }
  }
}
