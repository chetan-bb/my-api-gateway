package com.bigbasket.dvar;

import io.vertx.core.AbstractVerticle;
import com.bigbasket.dvar.HttpServerVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.logging.Logger;

public class MainVerticle extends AbstractVerticle {

  private static final Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class);

  @Override
  public void start(Promise<Void> promise) throws Exception {
    ConfigStoreOptions jsonStore = new ConfigStoreOptions()
      .setType("file")
      .setConfig(new JsonObject().put("path", "conf/conf.json"));

    ConfigRetrieverOptions CROptions = new ConfigRetrieverOptions()
            .addStore(jsonStore);

    ConfigRetriever retriever = ConfigRetriever.create(vertx, CROptions);

    retriever.getConfig(arConfig -> {
            if (arConfig.succeeded()) {
              JsonObject config = arConfig.result();

              Promise<String> httpVerticleDeployment = Promise.promise();
              DeploymentOptions options = new DeploymentOptions().setConfig(config).setInstances(2);

              vertx.deployVerticle(
                "com.bigbasket.dvar.HttpServerVerticle",
                options,
                httpVerticleDeployment);

              httpVerticleDeployment.future().setHandler(arHttp -> {
                if (arHttp.succeeded()) {
                  promise.complete();
                } else {
                  promise.fail(arHttp.cause());
                }
              });
            }
            else {
              LOGGER.error("Not able to read Config .. start failed", arConfig.cause());
              promise.fail(arConfig.cause());
            }
    });
}
}
