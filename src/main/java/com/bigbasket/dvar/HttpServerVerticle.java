package com.bigbasket.dvar;

import java.util.UUID;
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

import io.vertx.ext.auth.VertxContextPRNG;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.Base64;

public class HttpServerVerticle extends AbstractVerticle {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);
  private static final int DEFAULT_PORT = 8080;
  private static final String HEALTH_BASIC_URL = "/jdvar/v1/health";
  private static final String HULK_SVC_PREFIX = "/hulk/";
  private static final String APP_INTERNAL_URI_CONFIG_KEY = "client.app.external.internal.uri";
  private static final String WEB_INTERNAL_URI_CONFIG_KEY = "client.web.external.internal.uri";
  private static final String ANDROID_CHANNEL_HEADER = "BB-Android";
  private static final String IOS_CHANNEL_HEADER = "BB-IOS";
  private static final Base64.Encoder BASE64 = Base64.getMimeEncoder();
  private VertxContextPRNG random;
  private Mac mac;

  @Override
  public void start(Promise<Void> promise) throws Exception {
    try {
      String secret = "secret";
      random = VertxContextPRNG.current(vertx);
      mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new RuntimeException(e);
    }
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

  private void csrfGenerator(RoutingContext routingContext) {

  }

  private void test(RoutingContext routingContext) {
    byte[] salt = new byte[32];
    random.nextBytes(salt);
    String saltPlusToken = BASE64.encodeToString(salt) + "." + Long.toString(System.currentTimeMillis());
    String signature = BASE64.encodeToString(mac.doFinal(saltPlusToken.getBytes()));
    LOGGER.info(saltPlusToken + "." + signature);
    JsonObject resp = new JsonObject();
    resp.put("test", 1);
    routingContext.response().setStatusCode(200).end(Json.encodePrettily(resp));
  }

  private void doDispatch(RoutingContext routingContext) {
    try {
      HttpServerRequest clientRequest = routingContext.request();
      String uuid = clientRequest.getHeader("X-Tracker");
      LOGGER.info(uuid);
      if (uuid == null) {
        uuid = UUID.randomUUID().toString();
      }
      String host = clientRequest.host();
      host = config().getBoolean("prod.env", false)? host: "internal-" + host;
      LOGGER.info(host);
      int port = config().getInteger("api.gateway.http.port", DEFAULT_PORT);
      String path = "";
      String XChannelReqHeader = clientRequest.getHeader("x-channel");
      LOGGER.info(XChannelReqHeader);
      String externalURI = clientRequest.uri();
      String uriMapConfigKey = WEB_INTERNAL_URI_CONFIG_KEY;
      if (ANDROID_CHANNEL_HEADER.equals(XChannelReqHeader) || IOS_CHANNEL_HEADER.equals(XChannelReqHeader)){
        uriMapConfigKey = APP_INTERNAL_URI_CONFIG_KEY;
      }
      LOGGER.info(uriMapConfigKey);
      JsonObject uriConfig = config().getJsonObject(uriMapConfigKey, null);
      LOGGER.info(Json.encodePrettily(uriConfig));
      path = uriConfig.getString(externalURI, "/");
      LOGGER.info(path);
      HttpClient apiClient = vertx.createHttpClient(new HttpClientOptions());
      //todo remove
      clientRequest.headers().forEach(header -> {
        LOGGER.info(String.format("%s: %s",header.getKey(), header.getValue()));
      });

      //local testing
      path = "/test";
      host = "localhost";

      HttpClientRequest apiReq = apiClient
      .request(routingContext.request().method(), port, host, path, apiResponse -> {
        apiResponse.bodyHandler(body -> {
          if (apiResponse.statusCode() >= 500) {
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
      apiReq.putHeader("X-Caller", "DVAR-SVC");
      apiReq.putHeader("X-Tracker", uuid);
      // send request
      if (routingContext.getBody() == null) {
        apiReq.end();
      } else {
        apiReq.end(routingContext.getBody());
      }
    }catch (Exception e) {
      LOGGER.error(e);
      e.printStackTrace();
    }
  }
}
