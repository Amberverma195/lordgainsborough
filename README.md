# Lord Gainsborough Restaurant Project

This project consists of a Next.js frontend and a Java Spring Boot backend.

## Project Structure

- `frontend/`: Next.js 15+ application with TypeScript.
- `backend/`: Java 21 / Spring Boot 4 API backed exclusively by PostgreSQL 17.

## Getting Started

### Frontend

1. Navigate to the frontend directory:
   ```bash
   cd frontend
   ```
2. Install dependencies (already done):
   ```bash
   npm install
   ```
3. Run the development server:
   ```bash
   npm run dev
   ```

### Backend

1. Navigate to the backend directory:
   ```bash
   cd backend
   ```
2. Configure a development PostgreSQL database using the environment variables in
   `backend/src/main/resources/application.properties.example`: `DB_HOST`, `DB_PORT`,
   `DB_NAME`, `DB_USER`, and `DB_PASSWORD`, plus the mail and security secrets.
   Flyway applies the versioned schema; Hibernate validates it. No migration profile
   or persistence feature switches are required.
3. Run with the Maven wrapper (Java 21 or newer):
   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

Backend verification uses a dedicated local PostgreSQL 17 test cluster, without
containers. See [backend/TESTING.md](backend/TESTING.md) for isolation requirements
and the full regression command. No customer data import is needed.

## Tech Stack

- **Frontend**: Next.js, TypeScript, Framer Motion, Lucide React, Vanilla CSS.
- **Backend**: Java 21, Spring Boot 4, Spring Data JPA, PostgreSQL 17, Flyway, Socket.IO.
