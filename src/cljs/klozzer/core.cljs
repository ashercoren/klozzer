(ns klozzer.core
  (:require [cljs.core.async :refer [<! close! untap tap chan put!]]
            [klozzer.protocols :refer [IFileSystem -write -read -file-entry -url]])
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
      (!> fs.root.getFile
          filename
          (obj :create true)
          (fn [file-entry]
            (!> file-entry.createWriter
                (fn [writer]
                  (!> writer.write (js/Blob. #js [data] (obj :type "application/octet-binary")))
                  (put! c :done))
                (partial my-error-handler "createWriter" c))))
      c))
  (-file-entry [this filename]
    (let [c (chan)]
      (!> fs.root.getFile filename (obj :create false) 
          (fn [file-entry]
            (put! c file-entry))
          (partial my-error-handler "fs.root.getFile" c #{"NotFoundError"}))
      c))
  (-url [this filename]
    (go 
      (if-let [file-entry (<! (-file-entry this filename))]
        (!> file-entry.toURL )
        "")))
  (-read [this filename format]
    (let [c (chan)]
      (go 
        (let [file-entry (<! (-file-entry this filename))]
            (if file-entry
              (!> file-entry.file
                  (fn [file]
                    (let [reader (js/FileReader.)]
                      (! reader.onloadend (fn [e]
                                            (put! c (? e.target.result))))
                      (case format
                        "text" (!> reader.readAsText file)
                        "arraybuffer" (!> reader.readAsArrayBuffer file))))
                  (partial my-error-handler "file-entry.file" c))
              (close! c))))
      c)))

(defn new-storage [size-in-mb]
  (let [c (chan)]
    (!> js/navigator.webkitPersistentStorage.requestQuota (* size-in-mb 1024 1024)
        (fn [granted-bytes]
          (!> js/window.webkitRequestFileSystem js/PERSISTENT granted-bytes 
                                 #(put! c (FileSystem. %))
                                 (partial my-error-handler "webkitRequestFileSystem" c)))
        #(put! c [:error %]))
    c))



