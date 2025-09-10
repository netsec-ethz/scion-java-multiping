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

package org.scion.multiping.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.scion.jpan.*;

public class ScionProvider {

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

  private static class SyncDefault implements Sync {
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

  private final Supplier<Sync> senderSupplier;
  private final Function<ScmpSenderAsync.ResponseHandler, Async> senderAsyncSupplier;
  private final Supplier<List<ParseAssignments.HostEntry>> assignmentSupplier;
  private final Supplier<Long> localIsdAsSupplier;
  private final BiFunction<Long, InetSocketAddress, List<Path>> localDefaultPathsSupplier;

  public static ScionProvider defaultProvider(int localPort) {
    return new ScionProvider(
        () -> new SyncDefault(Scmp.newSenderBuilder().setLocalPort(localPort).build()),
        handler ->
            new AsyncDefault(Scmp.newSenderAsyncBuilder(handler).setLocalPort(localPort).build()),
        DownloadAssignmentsFromWeb::getList,
        () -> Scion.defaultService().getLocalIsdAs(),
        (isdAs, address) -> Scion.defaultService().getPaths(isdAs, address));
  }

  public static ScionProvider createSync(
      Supplier<Sync> senderSupplier,
      Function<ScmpSenderAsync.ResponseHandler, Async> senderAsyncSupplier,
      Supplier<List<ParseAssignments.HostEntry>> assignmentSupplier,
      Supplier<Long> localIsdAsSupplier,
      BiFunction<Long, InetSocketAddress, List<Path>> localDefaultPathsSupplier) {
    return new ScionProvider(
        senderSupplier,
        senderAsyncSupplier,
        assignmentSupplier,
        localIsdAsSupplier,
        localDefaultPathsSupplier);
  }

  private ScionProvider(
      Supplier<Sync> senderSupplier,
      Function<ScmpSenderAsync.ResponseHandler, Async> senderAsyncSupplier,
      Supplier<List<ParseAssignments.HostEntry>> assignmentSupplier,
      Supplier<Long> localIsdAsSupplier,
      BiFunction<Long, InetSocketAddress, List<Path>> localDefaultPathsSupplier) {
    this.assignmentSupplier = assignmentSupplier;
    this.senderSupplier = senderSupplier;
    this.senderAsyncSupplier = senderAsyncSupplier;
    this.localIsdAsSupplier = localIsdAsSupplier;
    this.localDefaultPathsSupplier = localDefaultPathsSupplier;
  }

  public List<ParseAssignments.HostEntry> getIsdAsEntries() {
    return assignmentSupplier.get();
  }

  public Async getAsync(ScmpSenderAsync.ResponseHandler handler) {
    return senderAsyncSupplier.apply(handler);
  }

  public Sync getSync() {
    return senderSupplier.get();
  }

  public List<Path> getPaths(long isdAs, InetSocketAddress destinationAddress) {
    return localDefaultPathsSupplier.apply(isdAs, destinationAddress);
  }

  public long getLocalIsdAs() {
    return localIsdAsSupplier.get();
  }
}
