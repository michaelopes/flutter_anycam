package br.dev.michaellopes.flutter_anycam.integration;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.flutter.plugin.common.EventChannel;

public class FlutterEventChannelRoutingTest {

    private final Object engineA = new Object();
    private final Object engineB = new Object();
    private final EventChannel.EventSink sinkA = new FakeSink();
    private final EventChannel.EventSink sinkB = new FakeSink();
    private FlutterEventChannel channel;

    @Before
    public void setUp() {
        channel = FlutterEventChannel.getInstance();
        channel.release();
    }

    @After
    public void tearDown() {
        channel.release();
    }

    @Test
    public void viewEventsGoToTheEngineThatCreatedTheView() {
        channel.attachSink(engineA, sinkA);
        channel.bindView(1, engineA);
        channel.attachSink(engineB, sinkB);

        assertSame(sinkA, channel.sinkFor(1));
    }

    @Test
    public void unboundEventsGoToTheMostRecentEngine() {
        channel.attachSink(engineA, sinkA);
        channel.attachSink(engineB, sinkB);

        assertSame(sinkB, channel.sinkFor(-3));
    }

    @Test
    public void detachingAnEngineFallsBackToThePreviousOne() {
        channel.attachSink(engineA, sinkA);
        channel.bindView(1, engineA);
        channel.attachSink(engineB, sinkB);

        channel.detachSink(engineB);

        assertSame(sinkA, channel.sinkFor(-3));
        assertSame(sinkA, channel.sinkFor(1));
    }

    @Test
    public void aViewOfADetachedEngineFallsBackToTheRemainingEngine() {
        channel.attachSink(engineA, sinkA);
        channel.attachSink(engineB, sinkB);
        channel.bindView(2, engineB);

        channel.detachSink(engineB);

        assertSame(sinkA, channel.sinkFor(2));
    }

    @Test
    public void aViewWhoseEngineStoppedListeningIsNotSentElsewhere() {
        channel.attachSink(engineA, sinkA);
        channel.bindView(1, engineA);
        channel.attachSink(engineB, sinkB);
        channel.bindView(1, engineB);

        channel.detachSink(engineB);
        channel.bindView(1, engineB);

        assertNull(channel.sinkFor(1));
    }

    @Test
    public void reattachingAnEngineMakesItTheMostRecent() {
        channel.attachSink(engineA, sinkA);
        channel.attachSink(engineB, sinkB);
        EventChannel.EventSink sinkA2 = new FakeSink();

        channel.attachSink(engineA, sinkA2);

        assertSame(sinkA2, channel.sinkFor(-3));
        channel.detachSink(engineA);
        assertSame(sinkB, channel.sinkFor(-3));
    }

    @Test
    public void releaseForgetsEverything() {
        channel.attachSink(engineA, sinkA);
        channel.bindView(1, engineA);

        channel.release();

        assertNull(channel.sinkFor(1));
        assertNull(channel.sinkFor(-3));
    }

    private static final class FakeSink implements EventChannel.EventSink {
        @Override
        public void success(Object event) {
        }

        @Override
        public void error(String errorCode, String errorMessage, Object errorDetails) {
        }

        @Override
        public void endOfStream() {
        }
    }
}
