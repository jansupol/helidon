/*
 * Copyright (c) 2017, 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.common.reactive;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link MultiFromOutputStream} test.
 */
public class OutputStreamMultiTest {

    @Test
    void testMulti() {
        StringBuilder result = new StringBuilder();
        MultiFromOutputStream osMulti = IoMulti.createOutputStream();

        Single<Void> multiFuture = osMulti.map(ByteBuffer::array)
                .map(String::new)
                .forEach(result::append);

        PrintWriter printer = new PrintWriter(osMulti);
        printer.print("test1");
        printer.print("test2");
        printer.print("test3");
        printer.close();

        multiFuture.await();
        assertThat(result.toString(), is(equalTo("test1test2test3")));
    }

    @Test
    void testMultiTimeout() {
        MultiFromOutputStream osMulti = IoMulti.builderOutputStream()
                .timeout(200, TimeUnit.MILLISECONDS)
                .build();

        TestSubscriber<ByteBuffer> testSubscriber = new TestSubscriber<>();
        osMulti.subscribe(testSubscriber);

        assertThrows(IOException.class, () -> osMulti.write("test".getBytes()));
    }

    @Test
    void testRequestCallback() throws IOException {
        MultiFromOutputStream osMulti = IoMulti.builderOutputStream()
                .build();

        TestSubscriber<String> testSubscriber = new TestSubscriber<>();

        osMulti.onRequest((n, demand) -> {
            for (int i = 1; i <= n; i++) {
                try {
                    osMulti.write(("test" + i).getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        })
                .map(ByteBuffer::array)
                .map(String::new)
                .subscribe(testSubscriber);

        testSubscriber.assertEmpty()
        .request(2)
        .assertValues("test1", "test2")
                .request(15)
                .assertItemCount(17)
                .request(3)
                .assertItemCount(20)
                .request(2000)
                .assertItemCount(2020)
                .request(2980)
                .assertItemCount(5000);

        osMulti.close();

        testSubscriber.assertComplete();
    }

    @Test
    public void testBasic() {
        MultiFromOutputStream publisher = IoMulti.createOutputStream();
        TestSubscriber<ByteBuffer> subscriber = new TestSubscriber<>();
        publisher.subscribe(subscriber);
        subscriber.requestMax();
        PrintWriter printer = new PrintWriter(publisher);
        printer.print("foo");
        printer.close();
        assertThat(subscriber.isComplete(), is(equalTo(true)));
        assertThat(subscriber.getLastError(), is(nullValue()));
        // Filter out any OutputStreamPublisher#FLUSH_BUFFER
        long size = subscriber.getItems().stream().filter(b -> b.capacity() > 0).count();
        assertThat(size, is(equalTo(1L)));
        ByteBuffer bb = subscriber.getItems().get(0);
        assertThat(new String(bb.array()), is(equalTo("foo")));
    }

    @Test
    void testSignalCloseCompleteWithException() {
        MultiFromOutputStream publisher = IoMulti.createOutputStream();
        publisher.signalCloseComplete(new IllegalStateException("foo!"));
        try {
            publisher.close();
            fail("an exception should have been thrown");
        } catch (IOException ex) {
            assertThat(ex.getCause(), is(not(nullValue())));
            assertThat(ex.getCause(), is(instanceOf(IllegalStateException.class)));
        }
    }

    @Test
    void testCloseOnNoDataWritten() throws IOException {
        MultiFromOutputStream publisher = IoMulti.createOutputStream();
        TestSubscriber<ByteBuffer> sub = new TestSubscriber<>();

        publisher.subscribe(sub);

        // this should return immediately
        publisher.close();

        sub.assertComplete();
        sub.assertItemCount(0);
    }

    @Test
    void testCancel() throws IOException {
        MultiFromOutputStream publisher = IoMulti.createOutputStream();
        TestSubscriber<ByteBuffer> sub = new TestSubscriber<>();

        publisher.subscribe(sub);
        sub.cancel();

        assertThrows(IOException.class, () -> publisher.write("Test".getBytes()));

        publisher.close();

        sub.assertEmpty();
    }

    @Test
    void testError() throws IOException {
        MultiFromOutputStream publisher = IoMulti.createOutputStream();
        TestSubscriber<ByteBuffer> sub = new TestSubscriber<>() {
            @Override
            public void onNext(ByteBuffer item) {
                throw new UnitTestException();
            }
        };

        publisher.subscribe(sub);
        sub.request(1);

        // need to make sure we do not block any method
        assertThrows(IOException.class, () -> publisher.write("Test".getBytes()));
        publisher.close();
    }

    private static final class UnitTestException extends RuntimeException {
        private UnitTestException() {
            super();
        }
    }
}
