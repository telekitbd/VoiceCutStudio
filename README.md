# VoiceCut Studio

Android (Kotlin + Jetpack Compose, Dark UI) অ্যাপ:

1. **Extract** — ভিডিও থেকে অডিও (M4A/AAC অথবা WAV)
2. **Transcript** — AssemblyAI দিয়ে টেক্সট, টাইমস্ট্যাম্প, Speaker 1/2 আলাদা করা; TXT / SRT / JSON এক্সপোর্ট
3. **Audio** — ভিডিওর অডিও সরানো বা নতুন অডিও বসিয়ে নতুন MP4

সব আউটপুট `Downloads/VoiceCut` ফোল্ডারে সেভ হয়।

## APK বিল্ড (GitHub)
1. এই ফোল্ডারটা একটা GitHub repo-তে push করুন
2. **Actions → Build APK → Run workflow** (push করলেও নিজে চলবে)
3. শেষ হলে **Artifacts** থেকে `VoiceCutStudio-debug-apk` ডাউনলোড করে ইনস্টল করুন

## API key
Transcript ট্যাবে assemblyai.com থেকে পাওয়া key বসান (ফোনে সংরক্ষিত থাকে)।

## সীমাবদ্ধতা
- MP3 আউটপুট নেই (Android-এ বিল্ট-ইন MP3 এনকোডার নেই; পরে LAME লাইব্রেরি যোগ করা যাবে)
- Transcript-এর জন্য ইন্টারনেট লাগে
