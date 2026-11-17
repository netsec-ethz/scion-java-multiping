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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.scion.jpan.Path;
import org.scion.jpan.Scmp;

public class MySync implements MPScionService.Sync {
  private final int sent;
  private int seqId = 0;

  public MySync(int sent) {
    this.sent = sent;
  }

  @Override
  public Scmp.EchoMessage sendEchoRequest(Path path, ByteBuffer bb) throws IOException {
    Scmp.EchoMessage msg = Scmp.EchoMessage.create(Scmp.TypeCode.TYPE_129, seqId++, seqId, path);
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
  public void close() {
    /* Nothing to do */
  }
}
