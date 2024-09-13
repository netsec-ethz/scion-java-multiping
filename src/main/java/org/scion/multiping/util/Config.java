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

import com.google.gson.Gson;
import java.io.*;

public class Config {
  public int attemptRepeatCnt = 5;
  public int attemptDelayMs = 100;
  public int roundRepeatCnt = 144; // 1 day
  public int roundDelaySec = 10 * 60; // 10 minutes
  public int maxPathsPerDestination = 20;
  public boolean tryICMP = false;

  public static Config read(String path) {
    Gson gson = new Gson();

    // Converts JSON file to Java object
    try (Reader reader = new FileReader(path)) {
      // Convert JSON File to Java Object
      return gson.fromJson(reader, Config.class);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  void write(String path) {
    Gson gson = new Gson();
    //      // Converts Java object to JSON string
    //      String json = gson.toJson(this);
    //      System.out.println("JSON: " + json);

    try (Writer writer = new FileWriter(path)) {
      gson.toJson(this, writer);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
