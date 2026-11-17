// Copyright 2026 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.multiping.util;

import java.io.IOException;
import java.util.function.Consumer;
import org.scion.jpan.Path;
import org.scion.jpan.ScmpSenderAsync;

public class MockHandler {
  @FunctionalInterface
  interface AsyncNoClose extends MPScionService.Async {
    @Override
    default void close() {}
  }

  public static class WithHandler implements AsyncNoClose {
    private int sequenceId = 0;
    ScmpSenderAsync.ResponseHandler handler;
    Consumer<ScmpSenderAsync.ResponseHandler> handlerConsumer;

    public WithHandler(
        ScmpSenderAsync.ResponseHandler handler,
        Consumer<ScmpSenderAsync.ResponseHandler> handlerConsumer) {
      this.handler = handler;
      this.handlerConsumer = handlerConsumer;
      // trigger handler
      handlerConsumer.accept(handler);
    }

    @Override
    public int sendTracerouteLast(Path path) throws IOException {
      return sequenceId++;
    }
  }

  public static class WithIOError extends WithHandler {
    public WithIOError(
        ScmpSenderAsync.ResponseHandler handler,
        Consumer<ScmpSenderAsync.ResponseHandler> handlerConsumer) {
      super(handler, handlerConsumer);
    }

    @Override
    public int sendTracerouteLast(Path path) throws IOException {
      throw new IOException("SCMP error");
    }
  }
}
