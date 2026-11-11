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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionUtil;

class DownloadTrcsTest {

  private static final String TRCS =
      "[{\"id\":{\"base_number\":1,\"isd\":64,\"serial_number\":1}},"
          + "{\"id\":{\"base_number\":1,\"isd\":64,\"serial_number\":2}},"
          + "{\"id\":{\"base_number\":1,\"isd\":71,\"serial_number\":5}}]";

  private static final String TRC_64 =
      "{\"authoritative_ases\":[\"64-2:0:9\"],"
          + "\"core_ases\":[\"64-2:0:9\",\"64-2:0:13\"],"
          + "\"description\":\"ISD 64\","
          + "\"id\":{\"base_number\":1,\"isd\":64,\"serial_number\":2},"
          + "\"validity\":{\"not_after\":\"2027-01-01T00:00:00Z\","
          + "\"not_before\":\"2026-01-01T00:00:00Z\"}}";

  // Plain AS numbers without ISD prefix should also be accepted.
  private static final String TRC_71 =
      "{\"authoritative_ases\":[\"2:0:35\"],"
          + "\"core_ases\":[\"2:0:35\"],"
          + "\"description\":\"ISD 71\","
          + "\"id\":{\"base_number\":1,\"isd\":71,\"serial_number\":5},"
          + "\"validity\":{\"not_after\":\"2027-01-01T00:00:00Z\","
          + "\"not_before\":\"2026-01-01T00:00:00Z\"}}";

  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    Map<String, String> files = new HashMap<>();
    files.put("/trcs", TRCS);
    files.put("/trcs/isd64-b1-s2", TRC_64);
    files.put("/trcs/isd71-b1-s5", TRC_71);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body = files.get(exchange.getRequestURI().getPath());
          byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private DownloadTrcs client() {
    return new DownloadTrcs("127.0.0.1:" + server.getAddress().getPort());
  }

  @Test
  void downloadIsds() throws IOException {
    List<ParseAssignments.HostEntry> isds = client().downloadIsds();
    assertEquals(2, isds.size());
    assertEquals(ScionUtil.parseIA("64-0"), isds.get(0).getIsdAs());
    assertEquals(ScionUtil.parseIA("71-0"), isds.get(1).getIsdAs());
  }

  @Test
  void downloadTrcs() throws IOException {
    List<DownloadTrcs.Trc> trcs = client().downloadTrcs();
    assertEquals(2, trcs.size());

    DownloadTrcs.Trc t64 = trcs.get(0);
    assertEquals(64, t64.getIsd());
    assertEquals(2, t64.getSerial()); // latest serial only
    assertEquals("ISD 64", t64.getDescription());
    assertEquals(2, t64.getCoreAses().size());
    assertTrue(t64.getCoreAses().contains(ScionUtil.parseIA("64-2:0:13")));
    assertEquals(ScionUtil.parseIA("64-2:0:9"), t64.getAuthoritativeAses().get(0));

    DownloadTrcs.Trc t71 = trcs.get(1);
    assertEquals(71, t71.getIsd());
    assertEquals(ScionUtil.parseIA("71-2:0:35"), t71.getCoreAses().get(0));
  }
}
