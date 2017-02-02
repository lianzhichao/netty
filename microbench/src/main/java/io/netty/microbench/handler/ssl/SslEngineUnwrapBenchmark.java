/*
 * Copyright 2017 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.microbench.handler.ssl;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import java.nio.ByteBuffer;

@State(Scope.Benchmark)
@Threads(1)
public class SslEngineUnwrapBenchmark extends AbstractSslEngineThroughputBenchmark {

    private ByteBuffer unwrapDstBuffer;

    @Override
    protected void doSetup()  {
        unwrapDstBuffer = allocateBuffer(serverEngine.getSession().getApplicationBufferSize());
    }

    @Override
    protected void doTearDown() {
        freeBuffer(unwrapDstBuffer);
    }

    @Override
    protected void doSetupInvocation() throws Exception {
        // Wrap one time so we can use the buffer to benchmark unwrap(...)
        doWrap();

        // Flip it so we can read it in unwrap()
        wrapDstBuffer.flip();

        // Clear it so we have enough space when calling unwrap()
        unwrapDstBuffer.clear();
    }

    @Benchmark
    public SSLEngineResult unwrap() throws SSLException {
        SSLEngineResult unwrapResult = serverEngine.unwrap(wrapDstBuffer, unwrapDstBuffer);

        assert checkSslEngineResult(unwrapResult, wrapDstBuffer, unwrapDstBuffer);
        return unwrapResult;
    }
}
