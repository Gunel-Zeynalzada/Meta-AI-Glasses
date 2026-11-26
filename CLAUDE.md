# GlassesAVCapture

Android app that captures audio/video from Ray-Ban Meta smart glasses. It supports the owner's research paper.

## Research paper (read before proposing designs or implementations)

- `docs/RPS_Meta_Smart_Glasses_paper.pdf` is the current research paper.
- Any solution must support what the paper describes. Check the paper before making design decisions, and tell the user if a request conflicts with it.
- If the PDF can't be read directly, ask the user to paste the relevant section.

## Notes

- `README.md` has the build status and the hardware checklist.
- After changing Gradle files, remind the user to click **Sync Now** in Android Studio.
- Builds are checked with `gradlew assembleDebug`. Running on the glasses or a phone is done from Android Studio.
