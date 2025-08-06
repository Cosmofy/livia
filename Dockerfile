FROM eclipse-temurin:24-jdk

# Install basic tools
RUN apt-get update && apt-get install -y curl unzip

# Set working directory
WORKDIR /app

# Copy source code
COPY . .

# Make Gradle wrapper executable
RUN chmod +x ./gradlew

# Expose port (adjust if needed)
EXPOSE 8080

# Run the app like Python (via Gradle)
CMD ["./gradlew", "bootRun"]