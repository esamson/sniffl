# sniffl

This is my goodbye to Flickr. Downloads all your photos and videos.

## To Use

You need:

* Java 8
* [sniffl JAR](https://github.com/esamson/sniffl/releases/download/v2/sniffl-v2.jar)

Launch:

```
$ java -jar sniffl-v2.jar
```

Works in the current directory unless a target directory is provided as an
argument.

```
$ java -jar sniffl-v2.jar /path/to/dir
```

## Features

1. Resumes. Just run again, pointing to the same directory. Successful
    downloads are cached.
2. Retries. Forever.
3. Concurrent downloads.
4. Organizes into directories according to your Flickr photosets.
5. Items not in any photoset are organized according to date taken.

## To Hack

You need:

* SBT
* Patience for someone else's messy code. ;)

This project is more or less "done", having served my singular use case. I'm
just sharing in the hope that it will be useful for others but I'm not likely
to work on further improvements.

If you'd like to share your own improvements, pull requests are most welcome
and will almost definitely be accepted.
