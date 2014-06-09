(ns ^:shared klozzer.protocols)

(defprotocol IFileSystem
  (-write-file [this filename data] "Writes a file with data")
  (-file-entry [this filename] "Retrieves the fileEntry javascript object")
  (-url-of-file [this filename] "Retrieves the url of the file")
  (-read-file [this filename] "Reads a file"))
