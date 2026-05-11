# Resumade Monolithic Backend

A consolidated Spring Boot application combining all 7 microservices into a single monolithic architecture.

## Architecture

This monolith consolidates the following services:
- **Auth Service** - Authentication, user management, JWT tokens
- **Resume Service** - Resume management and operations
- **AI Service** - AI-powered resume features
- **Export Service** - Resume export (PDF, DOCX)
- **Job Match Service** - Job matching and recommendations
- **Notification Service** - Email and notification handling
- **Template Service** - Resume templates

## Tech Stack

- **Framework**: Spring Boot 3.4.4
- **Language**: Java 21
- **Database**: MySQL (single shared database)
- **Cache**: Redis
- **Message Queue**: RabbitMQ
- **Authentication**: JWT (JJWT 0.12.6)
- **Documentation**: OpenAPI/Swagger
- **External APIs**: Google OAuth, Razorpay, Gemini AI, Jooble

## Building

```bash
mvn clean compile
mvn clean package -DskipTests
```

## Running

```bash
# Development
mvn spring-boot:run

# Production (with environment variables)
java -jar target/resumade-monolith-0.0.1-SNAPSHOT.jar
```

## Configuration

All services share a single `application.yml` configuration with unified:
- Database (single MySQL database: `resumade`)
- Redis connection
- RabbitMQ configuration
- JWT secrets and expiration
- External API credentials

### Environment Variables

```
# Database
DB_HOST=localhost
DB_PORT=3306
DB_NAME=resumade
DB_USER=Z4RY
DB_PASSWORD=ABmysql14

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# RabbitMQ
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=guest

# JWT
JWT_SECRET=resumade-super-secret-key-that-is-at-least-256-bits-long-for-hs256

# Google OAuth
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
GOOGLE_REDIRECT_URI=https://ai-resume-builder-frontend-sandy.vercel.app/auth/google

# Razorpay
RAZORPAY_KEY_ID=...
RAZORPAY_KEY_SECRET=...

# AWS S3
S3_BUCKET=resumade-exports
S3_REGION=us-east-1
AWS_ACCESS_KEY=...
AWS_SECRET_KEY=...

# Email
MAIL_HOST=smtp.gmail.com
MAIL_USER=...
MAIL_PASSWORD=...

# AI
GEMINI_API_KEY=...

# Job Matching
JOOBLE_API_KEY=...
```

## Project Structure

```
src/main/java/com/resumade/
├── auth/              # Authentication & user management
├── resume/            # Resume operations
├── ai/                # AI features
├── export/            # Export functionality
├── jobmatch/          # Job matching
├── notification/      # Notifications
└── template/          # Templates
```

Each service maintains its own package with:
- `controller/` - REST endpoints
- `service/` - Business logic
- `repository/` - Data access
- `entity/` - JPA entities
- `dto/` - Data transfer objects
- `exception/` - Custom exceptions
- `config/` - Service configuration
- `security/` - Security filters & utilities

## Default Port

- **Development**: 9090

## API Documentation

Swagger UI available at: `http://localhost:9090/swagger-ui.html`

## Database

Uses a single MySQL database with schemas for all entities. JPA Hibernate manages schema creation/updates via `ddl-auto: update`.

## Migration from Microservices

To migrate from the original microservices:

1. Backup existing individual databases
2. Create new unified `resumade` database
3. Run monolith with `ddl-auto: create-drop` to generate fresh schema
4. Migrate data using export scripts
5. Update frontend API URLs to port 9090
6. Update inter-service URLs to local endpoints

## Key Differences from Microservices

- **HTTP Calls** → **Local HTTP calls**: Services call local endpoints within the monolith
- **Separate databases** → **Single database**: All entities in one DB
- **Inter-service messaging** → **In-process**: No message broker required

## Deployment

Build and run as a standard Spring Boot application:

```bash
mvn package -DskipTests
java -jar target/resumade-monolith-0.0.1-SNAPSHOT.jar
```

## Testing

```bash
mvn test
```

## Logging

Configure via `application.yml` or set log levels:

```yaml
logging:
  level:
    com.resumade: DEBUG
    org.springframework: INFO
```

## Notes

- SecurityConfig from auth module applies globally for JWT validation
- Messaging runs inline; no broker required
- Redis caching available for auth module use
- Email notifications via Spring Mail with SMTP
