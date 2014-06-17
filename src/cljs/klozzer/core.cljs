(ns klozzer.core
  (:require [cljs.core.async :refer [<! close! untap tap chan put!]]
            [klozzer.protocols :refer [IFileSystem -write -read -file-entry -file -url]])
  (:use-macros [cljs.core.async.macros :only [go]]
               [purnam.core :only [? ! obj !>]]))


(defn my-error-handler 
  ([topic res e] 
    (my-error-handler topic res e #{}))
  ([topic res valid-error-names e] 
    (close! res)
    (when-not (valid-error-names (? e.name))
      (.log js/console topic (? e.message))
      (throw e))))

(defrecord FileSystem [fs]
  IFileSystem 
  (-write [this filename data]
    (let [c (chan)]
      (go
        (if-let [file-entry (<! (-file-entry this filename true))]
          (!> file-entry.createWriter
              (fn [writer]
                (! writer.onerror (partial my-error-handler "writer.write" c))
                (! writer.onwriteend #(put! c :done))
                (!> writer.write (js/Blob. #js [data] (obj :type "application/octet-binary"))))
              (partial my-error-handler "createWriter" c))
          (do (.log js/console "Cannot -write to: " filename)
              (close! c))))
      c))
  (-file-entry [this filename create?]
    (let [c (chan)]
      (!> fs.root.getFile filename (obj :create create?) 
          #(put! c %)
          (partial my-error-handler "fs.root.getFile" c #{"NotFoundError"}))
      c))
  (-file [this filename]
    (let [c (chan)]
      (go
        (if-let [file-entry (<! (-file-entry this filename false))]
          (!> file-entry.file #(put! c %))
          (close! c)))
      c))
  (-url [this filename]
    (go 
      (if-let [file-entry (<! (-file-entry this filename false))]
        (!> file-entry.toURL )
        "")))
  (-read [this filename format]
    (let [c (chan)]
      (go 
        (if-let [file (<! (-file this filename))]
          (let [reader (js/FileReader.)]
            (! reader.onloadend (fn [e]
                                  (put! c (? e.target.result))))
            (case format
              "text" (!> reader.readAsText file)
              "arraybuffer" (!> reader.readAsArrayBuffer file)))
          (close! c)))
      c)))

(defn new-storage [size-in-mb]
  (let [c (chan)]
    (!> js/navigator.webkitPersistentStorage.requestQuota (* size-in-mb 1024 1024)
        (fn [granted-bytes]
          (if (>= granted-bytes (* size-in-mb 1024 1024))
            (!> js/window.webkitRequestFileSystem js/PERSISTENT granted-bytes 
                #(put! c (FileSystem. %))
                (partial my-error-handler "webkitRequestFileSystem" c))
            (do 
              (.log js/console "requestQuota" "not enough granted bytes")
              (close! c))))
        (partial my-error-handler "webkitRequestFileSystem" c))
    c))
