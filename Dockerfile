FROM eclipse-temurin:24-jdk

# Install basic tools
RUN apt-get update && apt-get install -y curl unzip

# Set working directory
WORKDIR /app

# Copy source code
COPY . .

# Make Gradle wrapper executable
RUN chmod +x ./gradlew
RUN echo "Docker build reached: Gradle wrapper is executable"

# Expose port (adjust if needed)
EXPOSE 8080

# Run the app (via Gradle)
CMD ["./gradlew", "bootRun"]