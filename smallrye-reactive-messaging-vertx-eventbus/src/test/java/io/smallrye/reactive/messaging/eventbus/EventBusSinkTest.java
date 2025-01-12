package io.smallrye.reactive.messaging.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.core.Is.is;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.streams.operators.SubscriberBuilder;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.reactivestreams.Subscriber;

import io.reactivex.Flowable;
import io.smallrye.reactive.messaging.eventbus.codec.Person;
import io.smallrye.reactive.messaging.eventbus.codec.PersonCodec;

@RunWith(Parameterized.class)
public class EventBusSinkTest extends EventbusTestBase {

    private WeldContainer container;

    @Parameterized.Parameters
    public static Object[][] data() {
        return new Object[10][0];
    }

    @After
    public void cleanup() {
        if (container != null) {
            container.close();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectSinkWithoutAddress() {
        new EventBusSink(vertx, new MapBasedConfig(Collections.emptyMap()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectSinkWithPublishAndReply() {
        Map<String, Object> config = new HashMap<>();
        config.put("address", "hello");
        config.put("publish", true);
        config.put("expect-reply", true);
        new EventBusSink(vertx, new MapBasedConfig(config));
    }

    @Test
    public void testSinkUsingInteger() {
        String topic = UUID.randomUUID().toString();
        AtomicInteger expected = new AtomicInteger(0);
        usage.consumeIntegers(topic, 10, 10, TimeUnit.SECONDS,
                v -> expected.getAndIncrement());

        Map<String, Object> config = new HashMap<>();
        config.put("address", topic);
        EventBusSink sink = new EventBusSink(vertx, new MapBasedConfig(config));

        SubscriberBuilder<? extends Message, Void> subscriber = sink.sink();
        Flowable.range(0, 10)
                .map(v -> (Message) Message.of(v))
                .subscribe((Subscriber) subscriber.build());

        await().untilAtomic(expected, is(10));
        assertThat(expected).hasValue(10);
    }

    @Test
    public void testSinkUsingString() {
        String topic = UUID.randomUUID().toString();
        AtomicInteger expected = new AtomicInteger(0);
        usage.consumeStrings(topic, 10, 10, TimeUnit.SECONDS,
                v -> expected.getAndIncrement());

        Map<String, Object> config = new HashMap<>();
        config.put("address", topic);
        EventBusSink sink = new EventBusSink(vertx, new MapBasedConfig(config));

        SubscriberBuilder<? extends Message, Void> subscriber = sink.sink();
        Flowable.range(0, 10)
                .map(i -> Integer.toString(i))
                .map(Message::of)
                .subscribe((Subscriber) subscriber.build());
        await().untilAtomic(expected, is(10));
        assertThat(expected).hasValue(10);
    }

    @Test
    public void testPublish() {
        String topic = UUID.randomUUID().toString();
        AtomicInteger expected1 = new AtomicInteger(0);
        usage.consumeStrings(topic, 10, 10, TimeUnit.SECONDS,
                v -> expected1.getAndIncrement());

        AtomicInteger expected2 = new AtomicInteger(0);
        usage.consumeStrings(topic, 10, 10, TimeUnit.SECONDS,
                v -> expected2.getAndIncrement());

        Map<String, Object> config = new HashMap<>();
        config.put("address", topic);
        config.put("publish", true);
        EventBusSink sink = new EventBusSink(vertx, new MapBasedConfig(config));

        SubscriberBuilder<? extends Message, Void> subscriber = sink.sink();
        Flowable.range(0, 10)
                .map(i -> Integer.toString(i))
                .map(Message::of)
                .subscribe((Subscriber) subscriber.build());
        await().untilAtomic(expected1, is(10));
        assertThat(expected1).hasValue(10);
        assertThat(expected2).hasValue(10);
    }

    @Test
    public void testSendAndMultipleConsumers() {
        String topic = UUID.randomUUID().toString();
        AtomicInteger expected1 = new AtomicInteger(0);
        usage.consumeStrings(topic, 5, 10, TimeUnit.SECONDS,
                v -> expected1.getAndIncrement());

        AtomicInteger expected2 = new AtomicInteger(0);
        usage.consumeStrings(topic, 5, 10, TimeUnit.SECONDS,
                v -> expected2.getAndIncrement());

        Map<String, Object> config = new HashMap<>();
        config.put("address", topic);
        config.put("publish", false);
        EventBusSink sink = new EventBusSink(vertx, new MapBasedConfig(config));

        SubscriberBuilder<? extends Message, Void> subscriber = sink.sink();
        Flowable.range(0, 10)
                .map(i -> Integer.toString(i))
                .map(Message::of)
                .subscribe((Subscriber) subscriber.build());
        await().untilAtomic(expected1, is(5));
        await().untilAtomic(expected2, is(5));
        assertThat(expected1).hasValueBetween(4, 6);
        assertThat(expected2).hasValueBetween(4, 6);
    }

    @Test
    public void testExpectReply() {
        String topic = UUID.randomUUID().toString();

        List<Integer> integers = new ArrayList<>();
        AtomicReference<io.vertx.reactivex.core.eventbus.Message<Integer>> last = new AtomicReference<>();
        vertx.eventBus().<Integer> consumer(topic, m -> {
            last.set(m);
            if (m.body() < 8) {
                integers.add(m.body());
                m.reply("foo");
            }
        });

        Map<String, Object> config = new HashMap<>();
        config.put("address", topic);
        config.put("expect-reply", true);
        EventBusSink sink = new EventBusSink(vertx, new MapBasedConfig(config));

        SubscriberBuilder<? extends Message, Void> subscriber = sink.sink();
        Flowable.range(0, 10)
                .map(Message::of)
                .subscribe((Subscriber) subscriber.build());

        await().until(() -> integers.size() == 8 && last.get().body() == 8);
        last.get().reply("bar");
        await().until(() -> last.get().body() == 9);
        last.get().reply("baz");
    }

    @Test
    public void testCodec() {
        String topic = UUID.randomUUID().toString();

        List<Person> persons = new ArrayList<>();
        vertx.eventBus().<Person> consumer(topic, m -> {
            persons.add(m.body());
        });

        vertx.eventBus().getDelegate().registerCodec(new PersonCodec());

        Map<String, Object> config = new HashMap<>();
        config.put("address", topic);
        config.put("codec", "PersonCodec");
        EventBusSink sink = new EventBusSink(vertx, new MapBasedConfig(config));

        SubscriberBuilder<? extends Message, Void> subscriber = sink.sink();
        Flowable.range(0, 10)
                .map(i -> new Person().setName("name").setAge(i))
                .map(Message::of)
                .subscribe((Subscriber) subscriber.build());

        await().until(() -> persons.size() == 10);
    }

    @Test
    public void testABeanProducingMessagesSentToEventBus() {
        Weld weld = baseWeld();
        weld.addBeanClass(ProducingBean.class);
        weld.addBeanClass(VertxProducer.class);
        container = weld.initialize();
        ProducingBean bean = container.getBeanManager().createInstance().select(ProducingBean.class).get();

        await().until(() -> bean.messages().size() == 10);
    }

}
