# Dockerfile for lightweight Linux VM with OpenSSH and Java 26 (Eclipse Temurin)
FROM eclipse-temurin:26-jdk

# Avoid interactive prompts during package install
ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

# Install OpenSSH server, sudo, curl, sqlite3
RUN apt-get update && apt-get install -y \
    openssh-server \
    sudo \
    curl \
    sqlite3 \
    && rm -rf /var/lib/apt/lists/*

# Ensure Java is accessible in standard system PATH for SSH sessions
RUN ln -sf $JAVA_HOME/bin/* /usr/local/bin/ \
    && echo "export JAVA_HOME=${JAVA_HOME}" >> /etc/profile.d/java.sh \
    && echo "export PATH=\$JAVA_HOME/bin:\$PATH" >> /etc/profile.d/java.sh

# Create a non-root user for SSH access
RUN useradd -m -s /bin/bash crawler \
    && echo "crawler ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# Configure SSH: disable password auth, enable pubkey auth
RUN mkdir -p /var/run/sshd \
    && mkdir -p /etc/ssh/sshd_config.d \
    && echo "PasswordAuthentication no" > /etc/ssh/sshd_config.d/99-auth.conf \
    && echo "PubkeyAuthentication yes" >> /etc/ssh/sshd_config.d/99-auth.conf \
    && echo "AuthorizedKeysFile .ssh/authorized_keys" >> /etc/ssh/sshd_config.d/99-auth.conf \
    && ssh-keygen -A

# Create the crawler user's .ssh directory
RUN mkdir -p /home/crawler/.ssh \
    && chmod 700 /home/crawler/.ssh \
    && chown crawler:crawler /home/crawler/.ssh

# Working directory where the JAR will live
RUN mkdir -p /home/crawler/app \
    && chown crawler:crawler /home/crawler/app

WORKDIR /home/crawler/app

# Expose SSH and HTTP API ports
EXPOSE 22 8080

# Ensure volume directory permissions on container startup, then launch SSH daemon
CMD ["/bin/bash", "-c", "chown -R crawler:crawler /home/crawler/app && exec /usr/sbin/sshd -D"]
