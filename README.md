# Poucet: trace as data

A comprehensive tracer for Clojure.

## Usage

First ensure that the JVM is in debug mode (without a debugger attached yet): `-Xdebug -agentlib:jdwp=transport=dt_socket,server=y,suspend=n`.

Then at the repl, enter:

```clj
#_=> (require '[net.cgrand.poucet :refer [trace]])
#_=> (trace (+ 1 2))

{:method :root,
 :children
 [{:method ["user$eval140$fn__141" "invoke" nil -1],
   :children
   [{:method
     ["java.lang.ClassLoader"
      "checkPackageAccess"
      "java/lang/ClassLoader.java"
      488],
     :children
     [{:method
       ["java.lang.System"
        "getSecurityManager"
        "java/lang/System.java"
        334],
       :children [],
       :return nil}
      {:method
       ["java.util.HashSet" "add" "java/util/HashSet.java" 219],
       :children
       [{:method
         ["java.util.HashMap" "put" "java/util/HashMap.java" 611],
         :children
         [{:method
           ["java.util.HashMap" "hash" "java/util/HashMap.java" 338],
           :children
           [{:method
             ["java.lang.Object"
              "hashCode"
              "java/lang/Object.java"
              -1],
             :children [],
             :return nil}],
           :return nil}
          {:method
           ["java.util.HashMap" "putVal" "java/util/HashMap.java" 627],
           :children
           [{:method
             ["java.util.HashMap"
              "afterNodeAccess"
              "java/util/HashMap.java"
              1766],
             :children [],
             :return nil}],
           :return nil}],
         :return nil}],
       :return nil}],
     :return nil}
    {:method
     ["clojure.lang.Numbers" "add" "clojure/lang/Numbers.java" 1798],
     :children [],
     :return nil}
    {:method
     ["clojure.lang.Numbers" "num" "clojure/lang/Numbers.java" 1738],
     :children
     [{:method ["java.lang.Long" "valueOf" "java/lang/Long.java" 837],
       :children [],
       :return nil}],
     :return nil}],
   :return nil}],
 :return 3}

```

The trace is a data structure, not a printout.

The code is very alpha, more control should be given on the trace generation:

 * filtering (eg not tracing into classloading or clojure data-structure, if you play with `trace` you'll see that tracing `(into [] (map inc) (range 3))` is long because of the comprehensive tracing.
 * transformation of the data could be done as post-processing or on-the-fly (a reduce-inspired function is used to create the trace -- speaking of which the current impl is fully immutable so it doesn't help with the tracing speed).
 * arguments and return value capture (this adds a lot of overhead so it should be an option -- or cheaper ways such as bytecode transformation should be explored, taking care of not messing with SMAP (source mapping)).

## How Poucet compares to `tools.trace`?

`tools.trace` works at the var-level and only for vars you opted in.

Poucet works at the method-level and so traces any code, in any language.

## Beyond tracing

`Poucet` is built on top of JDI on purpose: to push the state of debugging in Clojure:

 • finer tracing (at the bytecode level rather than method level) could allow record/replay,
 • creating a true repl inside a breakpoint (or when an exception is about to be thrown)
 • ...

## License

Copyright © 2017 Christophe Grand

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
