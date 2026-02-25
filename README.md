# 🧠 Coding Agent

A modular AI Coding Agent built with Kotlin, designed with clean architecture principles, pluggable memory layers, and long-term vector storage.

This project demonstrates how to build a production-ready LLM agent with structured memory separation, local LLM integration, and persistent storage strategies.

---

## 🚀 Features

- Modular multi-module architecture
- Clean separation between core logic and infrastructure
- Pluggable memory system
    - SQLDelight (structured persistence)
    - Qdrant (vector-based long-term memory)
- Ollama integration for local LLM execution
- Ktor-based HTTP clients
- Docker support
- JVM 17

---

## 🏗 Architecture

The system follows a layered, modular architecture:

```
agent-core
│
├── domain (interfaces, models)
├── usecases
└── infrastructure abstractions
       
memory-sqldelight
│
└── structured conversation persistence

memory-qdrant
│
└── vector embedding + long-term memory retrieval

ollama-client
│
└── local LLM interaction

app
│
└── entry point / wiring
```

### Design Principles

- Dependency inversion
- Infrastructure isolated from domain
- Memory providers swappable
- No business logic in framework layer

---

## 🧠 Memory System

The agent separates memory into two layers:

### 1️⃣ Structured Memory (SQLDelight)

Used for:
- Conversation history
- Metadata
- Message tracking
- Deterministic retrieval

Benefits:
- Type-safe schema
- Compile-time SQL validation
- Lightweight SQLite backend

---

### 2️⃣ Long-Term Semantic Memory (Qdrant)

Used for:
- Vector embeddings
- Context retrieval
- Semantic similarity search

This enables:

- Persistent long-term memory
- Context injection across sessions
- Scalable memory growth

---

## 🔌 LLM Integration

The project uses:

- **Ollama** for local LLM hosting
- HTTP client via Ktor
- JSON serialization via kotlinx.serialization

The agent communicates with the model through a clean abstraction layer, allowing model replacement without touching domain logic.

---

## 🛠 Tech Stack

- Kotlin (JVM 17)
- Gradle Kotlin DSL
- SQLDelight 2.x
- Qdrant
- Ollama
- Ktor Client (CIO)
- kotlinx.serialization
- Docker

---

## ⚙️ Setup

### 1️⃣ Start Qdrant (Docker)

```bash
docker run -p 6333:6333 qdrant/qdrant
```

### 2️⃣ Start Ollama

```bash
ollama run llama3
```

### 3️⃣ Build Project

```bash
./gradlew build
```

### 4️⃣ Run Application

```bash
./gradlew run
```

---

## 📈 Why This Project Matters

This project demonstrates:

- Practical AI agent architecture
- Memory abstraction patterns
- Clean separation of concerns
- Real-world LLM system design
- Vector database integration

It is not a toy chatbot — it is an extensible agent foundation.

---

## 🔮 Future Improvements

- Embedding batching
- Memory pruning strategies
- Tool calling
- Streaming responses
- Multi-agent coordination
- REST API layer
- Observability (metrics + logging)

---

## 📄 License

MIT

---

## 👤 Author

Ivan Angelov  
Kotlin Developer | AI Systems | Clean Architecture
