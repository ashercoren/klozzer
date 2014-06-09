(ns klozzer.core
  (:require [cljs.core.async :refer [<! close! untap tap chan put!]]
            [klozzer.protocols :refer [IFileSystem -write -read -file-entry -url]])
  (:use-macros [cljs.core.async.macros :only [go]]
               [purnam.core :only [? ! obj !>]]))

(def errors {
             js/FileError.QUOTA_EXCEEDED_ERR "QUOTA_EXCEEDED_ERR"
             js/FileError.NOT_FOUND_ERR "NOT_FOUND_ERR"
             js/FileError.SECURITY_ERR "SECURITY_ERR"
             js/FileError.INVALID_MODIFICATION_ERR "INVALID_MODIFICATION_ERR"
             js/FileError.INVALID_STATE_ERR "INVALID_STATE_ERR"})


(defn my-error-handler [topic res e]
  (.log js/console topic (get errors e "Unknown Error")) (close! res) (throw e))

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
      (!> fs.root.getFile filename (clj->js {}) 
          (fn [file-entry]
            (put! c file-entry))
          (partial my-error-handler "fs.root.getFile" c))
      c))
  (-url [this filename]
    (go 
      (let [file-entry (<! (-file-entry this filename))]
        (!> file-entry.toURL ))))
  (-read [this filename]
    (let [c (chan)]
      (go 
        (let [file-entry (<! (-file-entry this filename))]
            (if file-entry
              (!> file-entry.file
                  (fn [file]
                    (let [reader (js/FileReader.)]
                      (! reader.onloadend (fn [e]
                                            (put! c (? e.target.result))))
                      (!> reader.readAsText file)))
                  (partial my-error-handler "file-entry.file" c))
              (close! c))))
      c)))

(defn new-storage [size-in-mb]
  (let [c (chan)]
    (!> js/navigator.webkitPersistentStorage.requestQuota (* size-in-mb 1024 1024)
        (fn [granted-bytes]
          (!> js/window.webkitRequestFileSystem js/PERSISTENT granted-bytes 
                                 #(put! c (FileSystem. %))
                                 #(put! c [:error (get errors % "Unknown error")])))
        #(put! c [:error %]))
    c))



