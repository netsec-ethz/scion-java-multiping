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

package org.scion.jpan;

import com.google.protobuf.ByteString;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.scion.jpan.proto.daemon.Daemon;

public class PathHelper {

  private PathHelper() {}

  public static List<Path> createPaths(int n) {
    List<Path> paths = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Daemon.Path.Builder builder = Daemon.Path.newBuilder();
      builder.setRaw(ByteString.copyFrom(new byte[] {}));
      try {
        InetAddress dst = InetAddress.getByAddress(new byte[] {123, 123, 123, 123});
        paths.add(RequestPath.create(builder.build(), n + 1, dst, 12345));
      } catch (UnknownHostException e) {
        throw new ScionRuntimeException("Unable to create test path", e);
      }
    }
    return paths;
  }
}
