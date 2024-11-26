// Copyright 2024 ETH Zurich
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

import static org.scion.multiping.util.Util.round;
import static org.scion.multiping.util.Util.sleep;

import com.google.common.util.concurrent.AtomicDouble;
import com.zaxxer.ping.IcmpPinger;
import com.zaxxer.ping.PingResponseHandler;
import com.zaxxer.ping.PingTarget;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import org.jetbrains.annotations.NotNull;

public class ICMP {
  public static int nIcmpTried = 0;
  public static int nIcmpSuccess = 0;
  public static int nIcmpError = 0;
  public static int nIcmpTimeout = 0;

  public static String pingICMP(InetAddress address, Config config) {
    if (!config.tryICMP) {
      return "OFF";
    }
    String ipStr = address.getHostAddress();
    if (address instanceof Inet4Address) {
      if (ipStr.startsWith("127.")
          || ipStr.startsWith("192.168.")
          || ipStr.startsWith("10.")
          || ipStr.startsWith("169.254.")) {
        return "N/A";
      }
      if (ipStr.startsWith("172.")) {
        String[] split = ipStr.split("\\.");
        int part2 = Integer.parseInt(split[1]);
        if (part2 >= 16 && part2 < 31) {
          return "N/A";
        }
      }
    }
    if (address instanceof Inet6Address && ipStr.startsWith("fd")) {
      return "N/A";
    }

    AtomicDouble seconds = new AtomicDouble(-2);
    PingResponseHandler handler =
        new PingResponseHandler() {
          @Override
          public void onResponse(@NotNull PingTarget pingTarget, double v, int i, int i1) {
            seconds.set(v);
          }

          @Override
          public void onTimeout(@NotNull PingTarget pingTarget) {
            seconds.set(-1);
          }
        };

    IcmpPinger pinger = new IcmpPinger(handler);
    PingTarget target = new PingTarget(address);
    Thread t = new Thread(pinger::runSelector);
    t.start();
    nIcmpTried++;

    pinger.ping(target);
    while (pinger.isPendingWork()) {
      sleep(100);
    }
    pinger.stopSelector();
    if (seconds.get() >= 0) {
      nIcmpSuccess++;
      double ms = seconds.get() * 1000;
      return round(ms, 2) + "ms"; // milliseconds
    }
    if (seconds.get() == -1) {
      nIcmpTimeout++;
      return "TIMEOUT";
    }
    nIcmpError++;
    return "ERROR";
  }
}
