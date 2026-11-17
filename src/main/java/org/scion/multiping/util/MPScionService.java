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
import java.util.List;
import org.scion.jpan.*;

public class MPScionService extends ScionService {
  List<ParseAssignments.HostEntry> isdAsAssignments;

  public static MPScionService defaultService() {
    ScionService.closeDefault();
    return ScionService.defaultService(
        new Builder().assignments(DownloadAssignmentsFromWeb.getList()));
  }

  protected MPScionService(Builder builder) {
    super(builder);
    isdAsAssignments = builder.assignments;
  }

  public List<ParseAssignments.HostEntry> getIsdAsEntries() {
    return isdAsAssignments;
  }

  public Sync newScmpSender(int localPort) {
    return new SyncDefault(ScmpSender.newBuilder().setLocalPort(localPort).build());
  }

  public Async newScmpSenderAsync(int localPort, ScmpSenderAsync.ResponseHandler handler) {
    return new AsyncDefault(ScmpSenderAsync.newBuilder(handler).setLocalPort(localPort).build());
  }

  protected static class Builder extends ScionService.Builder<MPScionService> {
    List<ParseAssignments.HostEntry> assignments;
    List<Long> localIsdAses;
    List<Path> localDefaultPaths;

    public Builder assignments(List<ParseAssignments.HostEntry> assignments) {
      this.assignments = assignments;
      return this;
    }

    public Builder localIsdAses(List<Long> localIsdAses) {
      this.localIsdAses = localIsdAses;
      return this;
    }

    public Builder localPaths(List<Path> localDefaultPaths) {
      this.localDefaultPaths = localDefaultPaths;
      return this;
    }

    @Override
    public MPScionService build() {

      return new MPScionService(this);
    }
  }

  public interface Async extends AutoCloseable {
    int sendTracerouteLast(Path path) throws IOException;

    void close() throws IOException;
  }

  public interface Sync extends AutoCloseable {
    Scmp.EchoMessage sendEchoRequest(Path path, ByteBuffer bb) throws IOException;

    List<Scmp.TracerouteMessage> sendTracerouteRequest(Path path) throws IOException;

    void close() throws IOException;
  }

  private static class AsyncDefault implements Async {
    private final ScmpSenderAsync sender;

    AsyncDefault(ScmpSenderAsync sender) {
      this.sender = sender;
    }

    @Override
    public int sendTracerouteLast(Path path) throws IOException {
      return sender.sendTracerouteLast(path);
    }

    @Override
    public void close() throws IOException {
      sender.close();
    }
  }

  protected static class SyncDefault implements Sync {
    private final ScmpSender sender;

    SyncDefault(ScmpSender sender) {
      this.sender = sender;
    }

    @Override
    public Scmp.EchoMessage sendEchoRequest(Path path, ByteBuffer bb) throws IOException {
      return sender.sendEchoRequest(path, bb);
    }

    @Override
    public List<Scmp.TracerouteMessage> sendTracerouteRequest(Path path) throws IOException {
      return sender.sendTracerouteRequest(path);
    }

    @Override
    public void close() throws IOException {
      sender.close();
    }
  }
}
