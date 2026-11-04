# pgvector use after a domain is agreed

Flyway enables `vector` in V1. Choose dimensions and an indexing strategy only after the embedding model is known. A future migration can add a `vector(n)` column and a cosine or inner product index. Keep the vector table with its owning domain.

For JPA, either register a supported Hibernate vector type and map the property after checking its version against Spring Boot 4, or keep vector writes and similarity searches in a repository with parameterized native SQL. Never concatenate vector values into SQL. Example query shape: `SELECT id FROM embeddings ORDER BY embedding <=> CAST(:query AS vector) LIMIT :limit`. Decide dimensions, distance metric, and index type with the AI team first.
