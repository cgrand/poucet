(ns net.cgrand.poucet)

(defonce trace-data
  (let [[_ pid] (re-matches #"([^@]*).*" (.getName (java.lang.management.ManagementFactory/getRuntimeMXBean)))
        cl (.getContextClassLoader (java.lang.Thread/currentThread))
        tools-jar (java.io.File. (System/getProperty "java.home") "../lib/tools.jar")
        sa-jdi-jar (java.io.File. (System/getProperty "java.home") "../lib/sa-jdi.jar")
        tools-jar-loader (doto (clojure.lang.DynamicClassLoader. cl) (.addURL (.toURL tools-jar))(.addURL (.toURL sa-jdi-jar)))
        trampoline (fn trampoline ; this is meant to always generate an event on exit ofthe thunk even when an exception is bubbling 
                     ([^long id f]
                       (try 
                         (.invokePrim ^clojure.lang.IFn$LOOO trampoline id (f) nil)
                         (catch Throwable t
                           (.invokePrim ^clojure.lang.IFn$LOOO trampoline id nil t))))
                     ([^long id r t]
                       (if t (throw t) r)))
        trampoline-classname (.getName (class trampoline))]
    clojure.java.api.Clojure ;  just there to force preparation
    (.setContextClassLoader (java.lang.Thread/currentThread) tools-jar-loader)
    (try
      (let [trace+
            (with-bindings {clojure.lang.Compiler/LOADER tools-jar-loader}
              (eval 
                `(let [^com.sun.jdi.connect.Connector conn#
                       (->> (com.sun.jdi.Bootstrap/virtualMachineManager) .attachingConnectors
                         (some #(when (-> ^com.sun.jdi.connect.Connector % .name (.contains "ProcessAttach")) %)))
                       args# (doto (.defaultArguments conn#)
                               (-> (get "pid") (.setValue ~pid))
                               (-> (get "timeout") (.setValue ~10000)))
                       vm# (.attach conn#  args#)
                       q# (.eventQueue vm#)
                       dispatch#
                       (-> #(while true 
                              (doseq [^com.sun.jdi.event.Event e# (.remove q#)]
                                (when-some [cb# (some-> e# .request (.getProperty "callback"))]
                                  (cb# e#))))
                         (Thread. "dispatch") .start)
                       erm# (.eventRequestManager vm#)
                       aid# (atom 0)
                       trace# 
                       (fn [trampoline# f# kvrf#]
                         (let [id# (swap! aid# inc)
                               acc# (atom (kvrf#))
                               min#
                               (doto (.createMethodEntryRequest erm#)
                                 (.setSuspendPolicy com.sun.jdi.request.EventRequest/SUSPEND_EVENT_THREAD)
                                 (.addClassExclusionFilter ~trampoline-classname))
                               mout#
                               (doto (.createMethodExitRequest erm#)
                                 (.setSuspendPolicy com.sun.jdi.request.EventRequest/SUSPEND_EVENT_THREAD)
                                 (.addClassExclusionFilter ~trampoline-classname))
                               mex#
                               (doto (.createExceptionRequest erm# nil true true)
                                 (.setSuspendPolicy com.sun.jdi.request.EventRequest/SUSPEND_EVENT_THREAD)
                                 (.addClassExclusionFilter ~trampoline-classname))
                               trace-start#
                               (doto (.createMethodEntryRequest erm#)
                                 (.addClassFilter ~trampoline-classname)
                                 (.setSuspendPolicy com.sun.jdi.request.EventRequest/SUSPEND_EVENT_THREAD))]
                           (doto trace-start#
                             (.putProperty "callback"
                               (fn [^com.sun.jdi.event.MethodEntryEvent e#]
                                 (when (and (= "invokePrim" (-> e# .method .name))
                                         (= id# (-> e# .thread (.frame 0) ^com.sun.jdi.LongValue (.getValue (-> e# .method .arguments (.get 0))) .value)))
                                   (case (-> e# .method .arguments .size)
                                     2 (let [thread# (.thread e#)
                                             ; the code below works but can't be on by default as it slows down tracing
                                             #_#_#_#_#_#_#_#_#_#_#_#_#_#_
                                             static-invoke#
                                             (fn [^com.sun.jdi.ClassType class# mname# msig# args#]
                                               (let [meth# (.concreteMethodByName class# mname# msig#)]
                                                 (.invokeMethod class# thread# meth# args#
                                                   com.sun.jdi.ClassType/INVOKE_SINGLE_THREADED)))
                                             invoke#
                                             (fn [^com.sun.jdi.ObjectReference obj# mname# msig# args#]
                                               (let [meth# (.concreteMethodByName (.referenceType obj#) mname# msig#)]
                                                 (.invokeMethod obj# thread# meth# args#
                                                   com.sun.jdi.ObjectReference/INVOKE_SINGLE_THREADED)))
                                             classloader# (-> thread# (.frame 0) .thisObject .referenceType .classLoader)
                                             ^com.sun.jdi.ClassObjectReference clojure# (invoke# classloader# "loadClass" "(Ljava/lang/String;)Ljava/lang/Class;"
                                                         [(.mirrorOf vm# "clojure.java.api.Clojure")])
                                             ^com.sun.jdi.ObjectReference trace-in# (-> clojure# .reflectedType
                                                          (static-invoke# "var" "(Ljava/lang/Object;)Lclojure/lang/IFn;"
                                                            [(.mirrorOf vm# "user/trace-in")]))
                                             invoke0# (.concreteMethodByName (.referenceType trace-in#) "invoke" "()Ljava/lang/Object;")
                                             tin# (fn []
                                                    (.invokeMethod trace-in# thread# invoke0# []
                                                      com.sun.jdi.ObjectReference/INVOKE_SINGLE_THREADED))
                                             min-frame-count# (+ 1 (.frameCount thread#))
                                             frame-count# (atom 0) ; relative
                                             ensure-frame-count!#
                                             (fn []
                                               (let [fc# (- (.frameCount thread#) min-frame-count#)]
                                                 (dotimes [_# (- @frame-count# fc#)]
                                                   (swap! acc# kvrf# :exit nil))
                                                 (reset! frame-count# fc#)))]
                                         (doto min#
                                           (.addThreadFilter thread#)
                                           (.putProperty "callback"
                                           (fn [^com.sun.jdi.event.MethodEntryEvent e#]
                                ;             (.disable min#) (.disable mout#) (.disable mex#) (tin#) (.enable min#) (.enable mout#) (.enable mex#)
                                             (when-not (neg? (ensure-frame-count!#))
                                               (swap! acc# kvrf# :enter
                                                 [(-> e# .method .declaringType .name) (-> e# .method .name) (-> e# .method .location (.sourcePath "Clojure") (try (catch com.sun.jdi.AbsentInformationException _#))) (-> e# .method .location (.lineNumber "Clojure") (try (catch com.sun.jdi.AbsentInformationException _#)))]))
                                             (.resume thread#)))
                                           .enable)
                                         (doto mout#
                                           (.addThreadFilter thread#)
                                           (.putProperty "callback"
                                             (fn [^com.sun.jdi.event.MethodExitEvent e#]
                                               (when-not (neg? (ensure-frame-count!#))
                                                 (swap! frame-count# dec)
                                                 (swap! acc# kvrf# :return nil))
                                               (-> e# .thread .resume)))
                                           .enable)
                                         (doto mex#
                                           (.addThreadFilter thread#)
                                           (.putProperty "callback"
                                             (fn [^com.sun.jdi.event.ExceptionEvent e#]
                                               (when-not (neg? (ensure-frame-count!#))
                                                 (swap! acc# kvrf# :throw (-> e# .exception .referenceType .name)))
                                               (-> e# .thread .resume)))
                                           .enable))
                                     3 (.deleteEventRequests erm# [trace-start# min# mout# mex#])))
                                 (-> e# .thread .resume)))
                             .enable)
                           (kvrf#
                             (try
                               (let [r# (trampoline# id# f#)] ; must be evaluated before @acc#
                                 (kvrf# @acc# :return r#))
                               (catch Throwable t#
                                 (-> @acc# (kvrf# :throw t#) (kvrf# :exit nil)))))))]
                   trace#)))]
        #(trace+ trampoline %
           (fn ; this is meant to be pluggable
             ([] [[[]] [:root]])
             ([[stack siblings]] (peek siblings))
             ([[stack siblings] op v]
               (case op
                 :enter [(conj stack siblings) [v]]
                 :return [(pop stack) (conj (peek stack) 
                                        {:method (nth siblings 0)
                                         :children (subvec siblings 1)
                                         :return v})]
                 :exit [(pop stack) (conj (peek stack)
                                      {:method (nth siblings 0)
                                       :children (subvec siblings 1)
                                       :exit true})]
                 :throw [stack (conj siblings [:throw v])])))))
     (finally  
       (.setContextClassLoader (java.lang.Thread/currentThread) cl)))))

(defmacro trace [expr] `(trace-data (fn [] ~expr)))
