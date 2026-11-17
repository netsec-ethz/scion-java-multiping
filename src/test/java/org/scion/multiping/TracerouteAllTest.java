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
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.multiping.util.*;

class TracerouteAllTest {

  @BeforeAll
  static void beforeAll() {
    TracerouteAll.FETCH_PUBLIC_ISDS = false;
  }

  @AfterAll
  static void afterAll() {
    TracerouteAll.FETCH_PUBLIC_ISDS = true;
  }

  @Test
  void testPingTimeout() throws IOException {
    List<Path> paths = PathHelper.createPaths(3);
    class MyWithHandler extends MockHandler.WithHandler {
      MyWithHandler(ScmpSenderAsync.ResponseHandler handler) {
        super(
            handler,
            hdl -> {
              for (int i = 0; i < 3; i++) {
                Scmp.TracerouteMessage req = Scmp.TracerouteMessage.createRequest(i, paths.get(i));
                Scmp.TracerouteMessage msg =
                    Scmp.TracerouteMessage.create(Scmp.TypeCode.TYPE_131, i, i, paths.get(i));
                msg.assignRequest(req, 1_000_000); // Hack: assign to itself
                msg.setTimedOut(1000 * 1000 * 1000);
                hdl.onTimeout(msg);
              }
            });
      }
    }

    MockService ms =
        new MockService.Builder()
            .syncSupplier(() -> new org.scion.multiping.util.MySync(3))
            .asyncSupplier(h -> new MyWithHandler(h))
            .assignments(Helper.isdAsList())
            .localIsdAses(Collections.singletonList(0L))
            .localPaths(PathHelper.createPaths(3))
            .build();
    TracerouteAll ping = new TracerouteAll(ms);
    ResultSummary summary = ping.run();
    assertEquals(3, summary.getAsTimeouts());
    // 3 ASes * 3 paths * 3 rounds
    assertEquals(27, summary.getPathTimeouts());
  }

  @Test
  void testPingErrorCode() throws IOException {
    List<Path> paths = PathHelper.createPaths(3);
    class MyWithHandler extends MockHandler.WithHandler {
      MyWithHandler(ScmpSenderAsync.ResponseHandler handler) {
        super(
            handler,
            hdl -> {
              for (int i = 0; i < 3; i++) {
                Scmp.ErrorMessage msg =
                    Scmp.ErrorMessage.create(Scmp.TypeCode.TYPE_5, paths.get(i));
                hdl.onError(msg);
              }
            });
      }
    }

    MockService ms =
        new MockService.Builder()
            .syncSupplier(() -> new MySync(3))
            .asyncSupplier(h -> new MyWithHandler(h))
            .assignments(Helper.isdAsList())
            .localIsdAses(Collections.singletonList(0L))
            .localPaths(PathHelper.createPaths(3))
            .build();
    TracerouteAll ping = new TracerouteAll(ms);
    ResultSummary summary = ping.run();
    assertEquals(3, summary.getMaxPaths().getPathCount());
    assertEquals(3, summary.getAsErrors());
    // 3 ASes * 3 paths * 3 rounds
    assertEquals(27, summary.getPathErrors());
  }

  @Test
  void testPingSendingError() throws IOException {
    class MyWithHandler extends MockHandler.WithIOError {
      MyWithHandler(ScmpSenderAsync.ResponseHandler handler) {
        super(handler, hdl -> {});
      }
    }

    MockService ms =
        new MockService.Builder()
            .syncSupplier(() -> new MySync(3))
            .asyncSupplier(h -> new MyWithHandler(h))
            .assignments(Helper.isdAsList())
            .localIsdAses(Collections.singletonList(0L))
            .localPaths(PathHelper.createPaths(3))
            .build();
    TracerouteAll ping = new TracerouteAll(ms);
    ResultSummary summary = ping.run();
    assertEquals(3, summary.getMaxPaths().getPathCount());
    assertEquals(3, summary.getAsErrors());
    // 3 ASes * 3 rounds, each round aborts after the first send
    assertEquals(9, summary.getPathErrors());
  }
}
