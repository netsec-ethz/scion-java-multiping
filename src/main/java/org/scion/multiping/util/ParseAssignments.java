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

import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class ParseAssignments {
  private static final Logger LOG = LoggerFactory.getLogger(ParseAssignments.class);

  // We use hostName/addressString as key.
  private final List<HostEntry> entries = new ArrayList<>();

  public static class HostEntry {
    private final long isdAs;
    private final String name;

    HostEntry(long isdAs, String name) {
      this.isdAs = isdAs;
      this.name = name;
    }

    public long getIsdAs() {
      return isdAs;
    }

    public String getName() {
      return name;
    }
  }

  private ParseAssignments() {}

  public void read(String fileName) {
    Path path = Paths.get(fileName);

    try (Stream<String> lines = Files.lines(path)) {
      lines.forEach(line -> parseLine(line, path));
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  private void parseLine(String line, Path path) {
    try {
      String s = line.trim();
      if (s.isEmpty() || s.startsWith("#")) {
        return;
      }
      String[] lineParts = s.split(",");
      long isdAs = ScionUtil.parseIA(lineParts[0].substring(1, lineParts[0].length() - 1));
      String name = lineParts[1];
      entries.add(new HostEntry(isdAs, name));
    } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
      LOG.info("ERROR parsing file {}: error=\"{}\" line=\"{}\"", path, e.getMessage(), line);
    }
  }

  public static List<HostEntry> getList(String fileName) {
    ParseAssignments pa = new ParseAssignments();
    pa.read(fileName);
    return pa.entries;
  }
}
