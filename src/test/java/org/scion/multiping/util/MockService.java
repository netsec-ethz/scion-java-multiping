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

import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.scion.jpan.Path;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScmpSenderAsync;

public class MockService extends MPScionService {
  final Supplier<Sync> syncSupplier;
  final Function<ScmpSenderAsync.ResponseHandler, Async> asyncSupplier;
  final List<ParseAssignments.HostEntry> assignments;
  final List<Long> localIsdAsList;
  final List<Path> localDefaultPaths;

  protected MockService(Builder builder) {
    super(builder);
    this.syncSupplier = builder.syncSupplier;
    this.asyncSupplier = builder.asyncSupplier;
    this.localIsdAsList = builder.localIsdAses;
    this.assignments = builder.assignments;
    this.localDefaultPaths = builder.localDefaultPaths;
  }

  @Override
  public List<Long> getLocalIsdAsList() {
    return localIsdAsList;
  }

  @Override
  public List<ParseAssignments.HostEntry> getIsdAsEntries() {
    return assignments;
  }

  @Override
  public List<Path> getPaths(long dstIsdAs, InetSocketAddress dstScionAddress) {
    return localDefaultPaths;
  }

  @Override
  public MPScionService.Sync newScmpSender(int localPort) {
    return syncSupplier.get();
  }

  @Override
  public MPScionService.Async newScmpSenderAsync(
      int localPort, ScmpSenderAsync.ResponseHandler handler) {
    return asyncSupplier.apply(handler);
  }

  public static class Builder extends MPScionService.Builder {
    Supplier<MPScionService.Sync> syncSupplier;
    Function<ScmpSenderAsync.ResponseHandler, MPScionService.Async> asyncSupplier;
    List<ParseAssignments.HostEntry> assignments;
    List<Long> localIsdAses;
    List<Path> localDefaultPaths;

    public Builder syncSupplier(Supplier<MPScionService.Sync> sync) {
      this.syncSupplier = sync;
      return this;
    }

    public Builder asyncSupplier(
        Function<ScmpSenderAsync.ResponseHandler, MPScionService.Async> async) {
      this.asyncSupplier = async;
      return this;
    }

    @Override
    public Builder assignments(List<ParseAssignments.HostEntry> assignments) {
      this.assignments = assignments;
      return this;
    }

    @Override
    public Builder localIsdAses(List<Long> localIsdAses) {
      this.localIsdAses = localIsdAses;
      return this;
    }

    @Override
    public Builder localPaths(List<Path> localDefaultPaths) {
      this.localDefaultPaths = localDefaultPaths;
      return this;
    }

    @Override
    public MockService build() {
      MockService ms = new MockService(this);
      ScionService.setDefaultService(ms);
      return ms;
    }
  }
}
