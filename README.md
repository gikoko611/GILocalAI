<div align="center">

<img src="docs/icon.png" width="140" alt="GI local AI icon">

# GI local AI

**Offline AI chat for Android. Load any GGUF model and talk to it with no internet.**



![Android](https://img.shields.io/badge/Android-7.0%2B-2dff8a?style=for-the-badge&logo=android&logoColor=black)




![ABI](https://img.shields.io/badge/ABI-armeabi--v7a-12b886?style=for-the-badge)




![Engine](https://img.shields.io/badge/Engine-llama.cpp-b6ff3d?style=for-the-badge)




![Offline](https://img.shields.io/badge/100%25-Offline-0a1610?style=for-the-badge)



</div>

---

## ✨ Features

| | Feature | Details |
|---|---|---|
| 📂 | **GGUF loader** | Pick any `.gguf` file from storage, no copying needed |
| 🔒 | **Fully offline** | Nothing leaves your phone |
| 🧠 | **AI memory** | Save notes the AI remembers between chats |
| 💬 | **Chat history** | Conversations stay after you close the app |
| ⚙️ | **Settings** | Temperature, max tokens, context size, CPU threads, system prompt |
| 🌐 | **Unicode safe** | Burmese and emoji text stream correctly |
| 🎨 | **Cyber green UI** | Dark green theme with an animated 3D **GI** intro |

## 📱 Made for

Redmi 9A and similar 32-bit phones (`armeabi-v7a`) with **4 GB RAM**.
Want 64-bit? Add `'arm64-v8a'` to `abiFilters` in `app/build.gradle`.

## 🤖 Recommended models (4 GB RAM)

| Model | Quant | File size | Notes |
|---|---|---|---|
| Llama 3.2 1B Instruct | Q4_K_M | ~0.8 GB | Fast, good starter |
| Qwen2.5 1.5B Instruct | Q4_K_M | ~1.1 GB | Better multilingual |
| Qwen2.5 0.5B Instruct | Q4_K_M | ~0.4 GB | Very light, fastest |

> 💡 Keep model files under about 1.2 GB. Use context size 1024 or lower. Close other apps before loading.
> Expect roughly 2 to 5 tokens per second on this class of phone.

## 🚀 Build the APK (GitHub Actions)

1. Open the **Actions** tab
2. Choose **Build APK** → **Run workflow**
3. Wait about 15 minutes
4. Download **GI-local-AI-apk** from **Artifacts**, unzip, install the `.apk`

## 🕹️ How to use

1. Open the app and wait for the 3D **GI** intro
2. Go to **Models** → **Load GGUF model** → pick your file
3. Go to **Chat** and start typing
4. Type `remember my name is Aung` to save a memory note
5. Tune generation in **Settings**

## 🗂️ Project structure

```
GILocalAI/
├── .github/workflows/build.yml     CI build
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── assets/index.html       UI (WebView)
│       ├── cpp/                    llama.cpp JNI bridge
│       ├── java/.../MainActivity   file picker and bridge
│       └── res/mipmap-*            app icon
└── README.md
```

## 🧩 How it works

```
 UI (HTML/JS)  ⇄  Kotlin bridge  ⇄  JNI (C++)  ⇄  llama.cpp  ⇄  your .gguf
```

## 🛠️ Troubleshooting

| Problem | Fix |
|---|---|
| App closes while loading | Model too big. Try a smaller quantization |
| "Chat is too long" | Clear the chat or raise Context size |
| Very slow replies | Lower context size, use a 0.5B or 1B model |
| Build fails | Open the failed step in Actions and check the last lines |

## 📌 Roadmap

- [ ] Export and import memory notes
- [ ] Multiple chat sessions
- [ ] Burmese UI language
- [ ] arm64-v8a build

---

<div align="center">

Built with ❤️ using [llama.cpp](https://github.com/ggml-org/llama.cpp)

</div>
