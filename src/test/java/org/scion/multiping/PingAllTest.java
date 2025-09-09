// Copyright 2025 ETH Zurich
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

package org.scion.multiping;

import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.multiping.util.Helper;
import org.scion.multiping.util.ScmpProvider;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PingAllTest {

  @FunctionalInterface
  interface AsyncNoClose extends ScmpProvider.Async {
    @Override
    default void close() {
    }
  }

  static class WithHandler implements AsyncNoClose {
    private final int sent;
    ScmpSenderAsync.ResponseHandler handler;
    Consumer<ScmpSenderAsync.ResponseHandler> handlerConsumer;

    WithHandler(int sent, ScmpSenderAsync.ResponseHandler handler, Consumer<ScmpSenderAsync.ResponseHandler> handlerConsumer) {
      this.sent = sent;
      this.handler = handler;
      this.handlerConsumer = handlerConsumer;
      handle(); // TODO ???
    }

    @Override
    public int sendTracerouteLast(Path path) throws IOException {
      return sent;
    }

    public void handle() {
      handlerConsumer.accept(handler);
    }
  }

  @Test
  void testPingNoSent() {
    List<Path> paths = PathHelper.createPaths(3);
    class MyWithHandler extends WithHandler {
      MyWithHandler(int sent, ScmpSenderAsync.ResponseHandler handler) {
        super(sent, handler, hdl -> {
          Scmp.TimedMessage msg0 = new Scmp.TracerouteMessage(Scmp.TypeCode.TYPE_131, 0, 0, 1, 1, paths.get(0));
          Scmp.TimedMessage msg1 = new Scmp.TracerouteMessage(Scmp.TypeCode.TYPE_131, 1, 1, 1, 1, paths.get(1));
          Scmp.TimedMessage msg2 = new Scmp.TracerouteMessage(Scmp.TypeCode.TYPE_131, 2, 2, 1, 1, paths.get(2));
          msg0.assignRequest(msg0, 1_000_000); // Hack: assign to itself
          msg1.assignRequest(msg1, 1_000_000);
          msg2.assignRequest(msg1, 1_000_000);
          hdl.onTimeout(msg0);
          hdl.onTimeout(msg1);
          hdl.onTimeout(msg2);
        });
      }
    }
    long local = ScionUtil.parseIA("1-234");
    ScmpProvider p = ScmpProvider.createAsync(h -> new MyWithHandler(0, h), Helper::isdAsList, () -> local, (ia, addr) -> PathHelper.createPaths(3));
    PingAll ping = new PingAll(PingAll.Policy.FASTEST_TR_ASYNC, p);
    assertThrows(NoSuchElementException.class, ping::run);
  }

  @Test
  void testPingTimeout() throws IOException {
    List<Path> paths = PathHelper.createPaths(3);
    class MyWithHandler extends WithHandler {
      MyWithHandler(int sent, ScmpSenderAsync.ResponseHandler handler) {
        super(sent, handler, hdl -> {
          // TODO try error codes!
          Scmp.TimedMessage msg0 = new Scmp.TracerouteMessage(Scmp.TypeCode.TYPE_131, 0, 0, 1, 1, paths.get(0));
          Scmp.TimedMessage msg1 = new Scmp.TracerouteMessage(Scmp.TypeCode.TYPE_131, 1, 1, 1, 1, paths.get(1));
          Scmp.TimedMessage msg2 = new Scmp.TracerouteMessage(Scmp.TypeCode.TYPE_131, 2, 2, 1, 1, paths.get(2));
          msg0.setTimedOut(1000 * 1000 * 1000);
          msg1.setTimedOut(1000 * 1000 * 1000);
          msg2.setTimedOut(1000 * 1000 * 1000);
//          msg0.setSendNanoSeconds(100);
//          msg1.setSendNanoSeconds(100);
//          msg2.setSendNanoSeconds(100);
          msg0.assignRequest(msg0, 1_000_000); // Hack: assign to itself
          msg1.assignRequest(msg1, 1_000_000);
          msg2.assignRequest(msg1, 1_000_000);
          hdl.onTimeout(msg0);
          hdl.onTimeout(msg1);
          hdl.onTimeout(msg2);
        });
      }
    }

    ScmpProvider p = ScmpProvider.createAsync(h -> new MyWithHandler(3, h), Helper::isdAsList, () -> 0L, (ia, addr) -> PathHelper.createPaths(3));
    PingAll ping = new PingAll(PingAll.Policy.FASTEST_TR_ASYNC, p);
    ping.run();
  }
}
