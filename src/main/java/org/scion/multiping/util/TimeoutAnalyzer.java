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

import static org.scion.multiping.util.Util.println;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.scion.jpan.*;

public class TimeoutAnalyzer {

  private static int nPathTried = 0;
  private static int nAsError = 0;
  private static int nPathTimeout = 0;
  private static int nPathSuccess = 0;

  private static Map<Integer, Long> idToPathIsdAs = new ConcurrentHashMap<>();
  private static Map<Integer, Path> idToPathMap = new ConcurrentHashMap<>();
  private static Map<Path, List<Integer>> pathToIdMap = new ConcurrentHashMap<>();

  private static Set<Long> seen = new HashSet<>();
  private static Map<Long, Integer> successes = new ConcurrentHashMap<>();
  private static Map<Long, Integer> failures = new ConcurrentHashMap<>();

  public static void analyze(List<Path> paths) {

    println("Analyzing: " + paths.size() + " paths");
    HashSet<Long> destinations = new HashSet<>();
    for (Path path : paths) {
      destinations.add(path.getRemoteIsdAs());
    }
    println("Destinations: " + destinations.size());
    for (Long isdAs : destinations) {
      println("  " + ScionUtil.toStringIA(isdAs));
    }

    analyzeInternal(paths);

    println("Seen: " + seen.size());
    for (Long isdAs : seen) {
      int success = successes.getOrDefault(isdAs, 0);
      int fail = failures.getOrDefault(isdAs, 0);
      println("  " + ScionUtil.toStringIA(isdAs) + "  " + success + " / " + fail);
    }

    println("Summary: ");
    println("  Path tried: " + nPathTried);
    println("  AS Errors: " + nAsError);
    println("  Timeouts: " + nPathTimeout);
    println("  Successes: " + nPathSuccess);
  }

  //  private static long getIsdAsAtHop(PathMetadata meta, int hopId) {
  //    if (meta.getInterfaces().isEmpty()) {
  //      return 0;
  //    }
  //    int nInterfaces = meta.getInterfaces().size();
  //    for (int i = 0; i < nInterfaces; i++) {
  //      PathMetadata.PathInterface pIf = meta.getInterfaces().get(i);
  //      if (i % 2 == 0) {
  //        sb.append(ScionUtil.toStringIA(pIf.getIsdAs())).append(" ");
  //        sb.append(pIf.getId()).append(">");
  //      } else {
  //        sb.append(pIf.getId()).append(" ");
  //      }
  //    }
  //    sb.append(ScionUtil.toStringIA(meta.getInterfaces().get(nInterfaces - 1).getIsdAs()));
  //    return sb.toString();
  //  }

  private static void analyzeInternal(List<Path> paths) {
    PingResponseHandler handler = new PingResponseHandler(paths.size());
    try (ScmpSenderAsync sender = ScmpSenderAsync.newBuilder(handler).build()) {
      for (Path path : paths) {
        nPathTried++;
        List<Integer> ids = sender.sendTraceroute(path);
        Util.sleep(5);
        pathToIdMap.put(path, ids);
        for (Integer id : ids) {
          if (idToPathMap.put(id, path) != null) {
            throw new IllegalStateException("Duplicate ID: " + id);
          }
          // ScionUtil.toStringPath()
          // path.getMetadata().getInterfaces().get(0).
          // idToPathIsdAs.put(id, path);
        }
      }

      // Wait for all messages to be received, BEFORE closing the "sender".
      // Wait for TIMEOUT + some extra to allow all timeouts to register
      Util.sleep(1000 + 500);
      // handler.await();
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nAsError++; // (isdAs);
      return;
    }

    if (handler.hasErrors() && handler.messages.isEmpty()) {
      nAsError++; // (isdAs);
      // return;
    }

    for (Scmp.TimedMessage tm : handler.messages.values()) {
      Scmp.TracerouteMessage msg = (Scmp.TracerouteMessage) tm;

      Path path = msg.getPath();
      long isdAs = path.getRemoteIsdAs();
      seen.add(isdAs);
      if (msg.isTimedOut()) {
        nPathTimeout++;
        failures.compute(isdAs, (isdAs2, count) -> count == null ? 1 : count + 1);
        continue;
      }

      successes.compute(isdAs, (isdAs2, count) -> count == null ? 1 : count + 1);
      nPathSuccess++;
    }
  }

  private static class PingResponseHandler implements ScmpSenderAsync.ResponseHandler {
    private final Map<Integer, Scmp.TimedMessage> messages = new ConcurrentHashMap<>();
    private final CountDownLatch barrier;
    private final AtomicInteger errors = new AtomicInteger();
    private final int nPaths;

    private PingResponseHandler(int nPaths) {
      this.nPaths = nPaths;
      barrier = new CountDownLatch(nPaths);
    }

    @Override
    public void onResponse(Scmp.TimedMessage msg) {
      barrier.countDown();
      messages.put(msg.getSequenceNumber(), msg);
    }

    @Override
    public void onTimeout(Scmp.TimedMessage msg) {
      barrier.countDown();
      messages.put(msg.getSequenceNumber(), msg);
    }

    @Override
    public void onError(Scmp.ErrorMessage msg) {
      errors.incrementAndGet();
      barrier.countDown();
    }

    @Override
    public void onException(Throwable t) {
      errors.incrementAndGet();
      barrier.countDown();
    }

    void await() {
      try {
        if (!barrier.await(1100, TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException("Missing messages: " + barrier.getCount() + "/" + nPaths);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    public boolean hasErrors() {
      return errors.get() > 0;
    }
  }
}
