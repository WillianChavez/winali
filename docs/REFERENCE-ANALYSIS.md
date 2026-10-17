# Reference analysis

Static analysis used REA 6.4.0 with JADX headless 0.7.1 (JDK 21), and Apktool 3.0.3. No reference application was modified, re-signed or published.

| Artifact | Observed identity | Static evidence |
| --- | --- | --- |
| ReVanced Manager Plus | com.revanced.net.revancedmanager 3.6.21 (381), min SDK 26 | ev_1addb865f65106c2649f8656e34673112fdfa5e53e8509d1ee4fd2a643b3b452 |
| APK Extractor | com.toralabs.apkextractor 6.0.2 | ev_f67a0857cbab9fafd2688fc63ea3acee11d14df1c89300d0d5425e534672aa2a |
| Focus To-Do | com.superelement.pomodoro 17.9 | ev_3188e51564d2fb1394ffc329d57c5a658088a5bb18f2c3f3eae673ac6bdd93e0 |
| Google Play Store | com.android.vending 53.5.48-34 | ev_6216b3f148cdacfaa6098f0bcbf79f84af1fd1741812c8e9d9f277fe29199e50 |

Focus's complete named-class inventory (evidence ev_9781b936f7498d2cfd70879876dcb8ff11312d93a7cddd2471322be307f82d16) identifies projects, folders, tags, task details, subtasks, reminders, calendar, reports, timer, strict mode, forest and seven widget families. Its decoded resource projection completed with ev_f8488dacc9647ab8fa074c262d80f019bde74aeaf9f07f05bfd3f070e5e6045f, confirming recurrence, ambient audio, voice reminders and concentration options. No proprietary strings or media are copied into the new apps.

APK Extractor's action-handler decompilation (ev_776be86190e88306e3956663c8d5fef23f0489b95d6e3f1154d7c81b8e4d8682 and ev_2647116c4c2280146fb58f54714f3b3de8a4004b30216d49aae88c3dea8d295c) establishes multi-selection extraction via Android's document tree picker, search and system-app filtering. Split archive support is a new design decision, not a verified reference feature.

These observations establish packaged declarations, resources and selected static relationships. They do not establish runtime behavior, complete feature parity or verified signatures of the reference APKs. Original artifacts and full local analysis output are intentionally excluded from public Git repositories.
