package io.smallrye.reactive.messaging.utils;

import org.reactivestreams.Subscription;

import java.util.Objects;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class WrappedSubscription implements Subscription {

  private final Subscription subscription;
  private final Runnable cancellationHandler;

  WrappedSubscription(Subscription subscription, Runnable onCancellation) {
    this.subscription = Objects.requireNonNull(subscription);
    this.cancellationHandler = onCancellation;
  }

  @Override
  public void request(long n) {
    subscription.request(n);
  }

  @Override
  public void cancel() {
    if (cancellationHandler != null) {
      cancellationHandler.run();
    }
    subscription.cancel();
  }
}
