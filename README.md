# 🧠 Coding Agent

Ein modular aufgebauter KI-Coding-Agent in Kotlin, entwickelt nach Clean-Architecture-Prinzipien mit austauschbaren Memory-Layern und persistentem Langzeitgedächtnis.

Dieses Projekt demonstriert, wie man einen produktionsnahen LLM-Agenten mit klarer Architektur, strukturierter Persistenz und Vektor-Datenbank-Integration entwickelt.

---

## 🚀 Features

- Mehrmodul-Architektur
- Klare Trennung zwischen Domain und Infrastruktur
- Austauschbares Memory-System
    - SQLDelight (strukturierte Persistenz)
    - Qdrant (vektorbasiertes Langzeitgedächtnis)
- Lokale LLM-Integration über Ollama
- Ktor HTTP-Client
- Docker-Support
- JVM 17

---

## 🏗 Architektur

Das System folgt einer modularen, geschichteten Architektur:

```
agent-core
│
├── domain (Interfaces, Modelle)
├── usecases
└── Infrastruktur-Abstraktionen
       
memory-sqldelight
│
└── Strukturierte Konversationspersistenz

memory-qdrant
│
└── Vektor-Embedding + semantische Langzeit-Speicherung

ollama-client
│
└── Lokale LLM-Integration

app
│
└── Entry Point / Dependency Wiring
```

### Architekturprinzipien

- Dependency Inversion
- Infrastruktur ist vom Domain-Layer entkoppelt
- Memory-Provider sind austauschbar
- Keine Business-Logik im Framework-Layer
- Klare Verantwortlichkeiten pro Modul

---

## 🧠 Memory-System

Der Agent trennt bewusst zwei Speicherarten:

---

### 1️⃣ Strukturierte Persistenz (SQLDelight)

Verwendet für:

- Gesprächsverlauf
- Metadaten
- Nachrichtenhistorie
- Deterministische Abfragen

Vorteile:

- Typensichere SQL-Definitionen
- Compile-Time-Validierung
- Leichtgewichtige SQLite-Integration

---

### 2️⃣ Semantisches Langzeitgedächtnis (Qdrant)

Verwendet für:

- Embeddings
- Kontext-Retrieval
- Semantische Ähnlichkeitssuche
- Sitzungsübergreifende Kontextanreicherung

Ermöglicht:

- Persistentes Langzeitgedächtnis
- Skalierbare Speicherarchitektur
- Kontextinjektion bei neuen Anfragen
- Trennung von Chat-Historie und Wissensspeicher

---

## 🔌 LLM-Integration

Die Modellanbindung erfolgt über:

- **Ollama** (lokales Hosting von LLMs)
- HTTP-Kommunikation via Ktor
- JSON-Serialisierung mit kotlinx.serialization

Die Modellkommunikation ist abstrahiert, sodass ein Modellwechsel möglich ist, ohne die Domain-Logik anzupassen.

---

## 🛠 Tech Stack

- Kotlin (JVM 17)
- Gradle Kotlin DSL
- SQLDelight 2.x
- Qdrant (Vector Database)
- Ollama
- Ktor Client (CIO)
- kotlinx.serialization
- Docker

---

## ⚙️ Setup

### 1️⃣ Qdrant starten (Docker)

```bash
docker run -p 6333:6333 qdrant/qdrant
```

### 2️⃣ Ollama starten

```bash
ollama run llama3
```

### 3️⃣ Projekt bauen

```bash
./gradlew build
```

### 4️⃣ Anwendung starten

```bash
./gradlew run
```

---

## 📈 Projektziel

Dieses Projekt demonstriert:

- Architektur für KI-Agenten
- Saubere Memory-Abstraktion
- Trennung zwischen strukturierter Persistenz und Vektorspeicher
- Integration lokaler Large Language Models
- Praxisnahe AI-Systemarchitektur in Kotlin

Es handelt sich nicht um einen einfachen Chatbot, sondern um eine erweiterbare Agent-Basis mit klarer Systemarchitektur.

---

## 🔮 Geplante Erweiterungen

- Embedding-Batching
- Memory-Pruning-Strategien
- Tool-Calling-Mechanismen
- Streaming-Responses
- REST-API
- Observability (Logging, Metriken)
- Multi-Agent-Strukturen

---

## 📄 Lizenz

MIT

---

## 👤 Autor

Ivan Angelov  
Kotlin Developer | AI-Systeme | Clean Architecture
