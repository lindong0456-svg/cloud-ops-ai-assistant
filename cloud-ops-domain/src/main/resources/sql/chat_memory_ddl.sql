-- 对话记忆持久化表
CREATE TABLE IF NOT EXISTS chat_memory (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id  VARCHAR(64) NOT NULL,
    seq         INT NOT NULL,
    role        VARCHAR(16) NOT NULL,
    content     TEXT NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
