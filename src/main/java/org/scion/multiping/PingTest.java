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

import com.zaxxer.ping.FailureReason;
import com.zaxxer.ping.IcmpPinger;
import com.zaxxer.ping.PingResponseHandler;
import com.zaxxer.ping.PingTarget;
import org.jetbrains.annotations.NotNull;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.scion.multiping.util.Util.*;

public class PingTest {
  public static void main(String[] args) throws UnknownHostException {
    PingResponseHandler handler =
            new PingResponseHandler() {
              @Override
              public void onFailure(
                      @NotNull PingTarget pingTarget, @NotNull FailureReason failureReason) {
                System.out.println("ICMP failed: " + failureReason);
              }

              @Override
              public void onResponse(@NotNull PingTarget pingTarget, double v, int i, int i1) {
                System.out.println("ICMP success: " + v + "  " + i + "  " + i1);
              }
            };

    //InetAddress address1 = Inet4Address.getByAddress(new byte[]{1, 1, 1, 1});
    //InetAddress address = Inet4Address.getByAddress(new byte[]{130, 59, 44, 218});
    InetAddress address = Inet4Address.getByName("130.59.44.218");
    IcmpPinger pinger = new IcmpPinger(handler);
    PingTarget target = new PingTarget(address);
    Thread t = new Thread(pinger::runSelector);
    t.start();
    //   sleep(50);

    pinger.ping(target);
    while (pinger.isPendingWork()) {
      sleep(100);
    }
    pinger.stopSelector();


  }
}
