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

public class Util {

  public static boolean PRINT = true;

  public static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  public static void print(String msg) {
    if (PRINT) {
      System.out.print(msg);
    }
  }

  public static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }

  public static double round(double d, int nDigits) {
    double div = Math.pow(10, nDigits);
    return Math.round(d * div) / div;
  }

  public static class Ref<T> {
    public T t;

    private Ref(T t) {
      this.t = t;
    }

    public static <T> Ref<T> empty() {
      return new Ref<>(null);
    }

    public static <T> Ref<T> of(T t) {
      return new Ref<>(t);
    }

    public T get() {
      return t;
    }

    public void set(T t) {
      this.t = t;
    }
  }
}
