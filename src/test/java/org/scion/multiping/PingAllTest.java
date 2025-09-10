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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.multiping.util.Helper;
import org.scion.multiping.util.ResultSummary;
import org.scion.multiping.util.ScionProvider;

class PingAllTest {

  @FunctionalInterface
  interface AsyncNoClose extends ScionProvider.Async {
    @Override
    default void close() {}
  }

  static class WithHandler implements AsyncNoClose {
    private int sequenceId = 0;
    ScmpSenderAsync.ResponseHandler handler;
    Consumer<ScmpSenderAsync.ResponseHandler> handlerConsumer;

    WithHandler(
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

  static class WithIOError extends WithHandler {
    WithIOError(
        ScmpSenderAsync.ResponseHandler handler,
        Consumer<ScmpSenderAsync.ResponseHandler> handlerConsumer) {
      super(handler, handlerConsumer);
    }

    @Override
    public int sendTracerouteLast(Path path) throws IOException {
      throw new IOException("SCMP error");
    }
  }

  static class MySync implements ScionProvider.Sync {
    private final int sent;
    private int seqId = 0;

    MySync(int sent) {
      this.sent = sent;
    }

    @Override
    public Scmp.EchoMessage sendEchoRequest(Path path, ByteBuffer bb) throws IOException {
      Scmp.EchoMessage msg = Scmp.EchoMessage.createEmpty(path);
      msg.setMessageArgs(Scmp.TypeCode.TYPE_129, seqId++, seqId);
      msg.assignRequest(msg, 1_000_000); // Hack: assign to itself
      return msg;
    }

    @Override
    public List<Scmp.TracerouteMessage> sendTracerouteRequest(Path path) throws IOException {
      List<Scmp.TracerouteMessage> msgs = new ArrayList<>();
      int sID = seqId++;
      for (int i = 0; i < sent; i++) {
        Scmp.TracerouteMessage msg =
            new Scmp.TracerouteMessage(Scmp.TypeCode.TYPE_131, sID, sID, 1, 1, path);
        msg.assignRequest(msg, 1_000_000); // Hack: assign to itself
        msgs.add(msg);
      }
      return msgs;
    }

    @Override
    public void close() {}
  }

  @Test
  void testPingTimeout() throws IOException {
    List<Path> paths = PathHelper.createPaths(3);
    class MyWithHandler extends WithHandler {
      MyWithHandler(ScmpSenderAsync.ResponseHandler handler) {
        super(
            handler,
            hdl -> {
              for (int i = 0; i < 3; i++) {
                Scmp.TimedMessage req = Scmp.TracerouteMessage.createRequest(i, paths.get(i));
                Scmp.TimedMessage msg = Scmp.TracerouteMessage.createEmpty(paths.get(i));
                msg.setMessageArgs(Scmp.TypeCode.TYPE_131, i, i);
                msg.assignRequest(req, 1_000_000); // Hack: assign to itself
                msg.setTimedOut(1000 * 1000 * 1000);
                hdl.onTimeout(msg);
              }
            });
      }
    }

    ScionProvider p =
        ScionProvider.createSync(
            () -> new MySync(3),
            MyWithHandler::new,
            Helper::isdAsList,
            () -> 0L,
            (ia, addr) -> PathHelper.createPaths(3));
    PingAll ping = new PingAll(PingAll.Policy.FASTEST_TR_ASYNC, p);
    ResultSummary summary = ping.run();
    assertEquals(3, summary.getAsTimeouts());
    assertEquals(9, summary.getPathTimeouts());
  }

  @Test
  void testPingErrorCode() throws IOException {
    List<Path> paths = PathHelper.createPaths(3);
    class MyWithHandler extends WithHandler {
      MyWithHandler(ScmpSenderAsync.ResponseHandler handler) {
        super(
            handler,
            hdl -> {
              for (int i = 0; i < 3; i++) {
                Scmp.ErrorMessage msg =
                    Scmp.ErrorMessage.createEmpty(Scmp.TypeCode.TYPE_5, paths.get(i));
                hdl.onError(msg);
              }
            });
      }
    }

    ScionProvider p =
        ScionProvider.createSync(
            () -> new MySync(3),
            MyWithHandler::new,
            Helper::isdAsList,
            () -> 0L,
            (ia, addr) -> PathHelper.createPaths(3));
    PingAll ping = new PingAll(PingAll.Policy.FASTEST_TR_ASYNC, p);
    ResultSummary summary = ping.run();
    assertEquals(0, summary.getMaxPaths().getPathCount());
    assertEquals(9, summary.getAsErrors());
  }

  @Test
  void testPingSendingError() throws IOException {
    List<Path> paths = PathHelper.createPaths(3);
    class MyWithHandler extends WithIOError {
      MyWithHandler(ScmpSenderAsync.ResponseHandler handler) {
        super(handler, hdl -> {});
      }
    }

    ScionProvider p =
        ScionProvider.createSync(
            () -> new MySync(3),
            MyWithHandler::new,
            Helper::isdAsList,
            () -> 0L,
            (ia, addr) -> PathHelper.createPaths(3));
    PingAll ping = new PingAll(PingAll.Policy.FASTEST_TR_ASYNC, p);
    ResultSummary summary = ping.run();
    assertEquals(0, summary.getMaxPaths().getPathCount());
    assertEquals(3, summary.getAsErrors());
  }
}
