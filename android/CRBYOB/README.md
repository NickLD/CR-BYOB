# CR-BYOB Android app

The app's source: plain Java, built with Gradle (wrapper in `android/`).

```bash
cd android && ./gradlew assemble        # all variants
cd android/CRBYOB && ./build.sh         # debug build, install on the connected phone and launch
```

- [Building from source](../../docs/building.md): flavors, signing, versions, debug builds and logs.
- [How it works](../../docs/how-it-works.md): protocols and a map of the source files.
- [Using the app](../../docs/using-the-app.md)
