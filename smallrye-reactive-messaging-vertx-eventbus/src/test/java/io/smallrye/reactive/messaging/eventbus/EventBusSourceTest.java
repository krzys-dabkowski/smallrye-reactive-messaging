package io.smallrye.reactive.messaging.eventbus;

import io.reactivex.Flowable;
import io.smallrye.reactive.messaging.MediatorFactory;
import io.smallrye.reactive.messaging.ReactiveMessagingExtension;
import io.smallrye.reactive.messaging.impl.ConfiguredStreamFactory;
import io.smallrye.reactive.messaging.impl.InternalStreamRegistry;
import io.smallrye.reactive.messaging.impl.StreamFactoryImpl;
import io.smallrye.reactive.messaging.spi.ConfigurationHelper;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.After;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class EventBusSourceTest extends EventbusTestBase {

  private WeldContainer container;

  @After
  public void cleanup() {
    if (container != null) {
      container.close();
    }
  }


  @Test
  public void testSource() {
    String topic = UUID.randomUUID().toString();
    Map<String, String> config = new HashMap<>();
    config.put("address", topic);
    EventBusSource source = new EventBusSource(vertx, ConfigurationHelper.create(config));

    List<EventBusMessage> messages = new ArrayList<>();
    Flowable.fromPublisher(source.publisher()).cast(EventBusMessage.class).forEach(messages::add);
    AtomicInteger counter = new AtomicInteger();
    new Thread(() ->
      usage.produceIntegers(topic, 10, true, null,
        counter::getAndIncrement)).start();

    await().atMost(2, TimeUnit.MINUTES).until(() -> messages.size() >= 10);
    assertThat(messages.stream()
      .map(EventBusMessage::getPayload)
      .collect(Collectors.toList()))
      .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
  }

  @Test
  public void testMulticast() {
    String topic = UUID.randomUUID().toString();
    Map<String, String> config = new HashMap<>();
    config.put("address", topic);
    config.put("multicast", "true");
    EventBusSource source = new EventBusSource(vertx, ConfigurationHelper.create(config));

    List<EventBusMessage> messages1 = new ArrayList<>();
    List<EventBusMessage> messages2 = new ArrayList<>();
    Flowable<EventBusMessage> flowable = Flowable.fromPublisher(source.publisher()).cast(EventBusMessage.class);
    flowable.forEach(messages1::add);
    flowable.forEach(messages2::add);

    AtomicInteger counter = new AtomicInteger();
    new Thread(() ->
      usage.produceIntegers(topic, 10, true, null,
        counter::getAndIncrement)).start();

    await().atMost(2, TimeUnit.MINUTES).until(() -> messages1.size() >= 10);
    await().atMost(2, TimeUnit.MINUTES).until(() -> messages2.size() >= 10);
    assertThat(messages1.stream().map(EventBusMessage::getPayload)
      .collect(Collectors.toList()))
      .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    assertThat(messages2.stream().map(EventBusMessage::getPayload)
      .collect(Collectors.toList()))
      .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
  }

  @Test
  public void testABeanConsumingTheEventBusMessages() {
    ConsumptionBean bean = deploy();
    await().until(() -> container.getBeanManager().getExtension(ReactiveMessagingExtension.class).isInitialized());

    List<Integer> list = bean.getResults();
    assertThat(list).isEmpty();

    bean.produce();

    await().atMost(2, TimeUnit.MINUTES).until(() -> list.size() >= 10);
    assertThat(list).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
  }

  private ConsumptionBean deploy() {
    Weld weld = new Weld();
    weld.addBeanClass(MediatorFactory.class);
    weld.addBeanClass(InternalStreamRegistry.class);
    weld.addBeanClass(StreamFactoryImpl.class);
    weld.addBeanClass(ConfiguredStreamFactory.class);
    weld.addExtension(new ReactiveMessagingExtension());
    weld.addBeanClass(VertxEventBusMessagingProvider.class);
    weld.addBeanClass(ConsumptionBean.class);
    weld.disableDiscovery();
    container = weld.initialize();
    return container.getBeanManager().createInstance().select(ConsumptionBean.class).get();
  }

}