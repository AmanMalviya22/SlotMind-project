 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v3.3.5)

2026-11-04 14:28:23.587 INFO  [main] com.slotmind.SlotMindApplication - Starting SlotMindApplication using Java 21.0.12.1 with PID 20572 (C:\Users\zycus\Desktop\zycus\SlotMind-project\backend\target\classes started by zycus in C:\Users\zycus\Desktop\zycus\SlotMind-project\backend)
2026-11-04 14:28:23.589 INFO  [main] com.slotmind.SlotMindApplication - No active profile set, falling back to 1 default profile: "default"
2026-11-04 14:28:24.232 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate - Bootstrapping Spring Data JPA repositories in DEFAULT mode.
2026-11-04 14:28:24.309 INFO  [main] o.s.d.r.c.RepositoryConfigurationDelegate - Finished Spring Data repository scanning in 54 ms. Found 4 JPA repository interfaces.
2026-11-04 14:28:24.707 INFO  [main] o.s.b.w.e.tomcat.TomcatWebServer - Tomcat initialized with port 8080 (http)
2026-11-04 14:28:24.723 INFO  [main] o.a.catalina.core.StandardService - Starting service [Tomcat]
2026-11-04 14:28:24.723 INFO  [main] o.a.catalina.core.StandardEngine - Starting Servlet engine: [Apache Tomcat/10.1.31]
2026-11-04 14:28:24.774 INFO  [main] o.a.c.c.C.[Tomcat].[localhost].[/] - Initializing Spring embedded WebApplicationContext
2026-11-04 14:28:24.774 INFO  [main] o.s.b.w.s.c.ServletWebServerApplicationContext - Root WebApplicationContext: initialization completed in 1144 ms
2026-11-04 14:28:24.874 INFO  [main] o.h.jpa.internal.util.LogHelper - HHH000204: Processing PersistenceUnitInfo [name: default]
2026-11-04 14:28:24.923 INFO  [main] org.hibernate.Version - HHH000412: Hibernate ORM core version 6.5.3.Final
2026-11-04 14:28:24.940 INFO  [main] o.h.c.i.RegionFactoryInitiator - HHH000026: Second-level cache disabled
2026-11-04 14:28:25.168 INFO  [main] o.s.o.j.p.SpringPersistenceUnitInfo - No LoadTimeWeaver setup: ignoring JPA class transformer
2026-11-04 14:28:25.191 INFO  [main] com.zaxxer.hikari.HikariDataSource - HikariPool-1 - Starting...
2026-11-04 14:28:25.323 INFO  [main] com.zaxxer.hikari.pool.HikariPool - HikariPool-1 - Added connection conn0: url=jdbc:h2:mem:slotmind user=SA
2026-11-04 14:28:25.323 INFO  [main] com.zaxxer.hikari.HikariDataSource - HikariPool-1 - Start completed.
2026-11-04 14:28:26.024 INFO  [main] o.h.e.t.j.p.i.JtaPlatformInitiator - HHH000489: No JTA platform available (set 'hibernate.transaction.jta.platform' to enable JTA platform integration)
2026-11-04 14:28:26.091 INFO  [main] o.s.o.j.LocalContainerEntityManagerFactoryBean - Initialized JPA EntityManagerFactory for persistence unit 'default'
2026-11-04 14:28:26.302 INFO  [main] o.s.d.j.r.query.QueryEnhancerFactory - Hibernate is in classpath; If applicable, HQL parser will be used.
2026-11-04 14:28:26.943 INFO  [main] o.s.b.w.e.tomcat.TomcatWebServer - Tomcat started on port 8080 (http) with context path '/'
2026-11-04 14:28:26.943 INFO  [main] com.slotmind.SlotMindApplication - Started SlotMindApplication in 3.654 seconds (process running for 3.954)
2026-11-04 14:28:36.068 INFO  [http-nio-8080-exec-4] o.a.c.c.C.[Tomcat].[localhost].[/] - Initializing Spring DispatcherServlet 'dispatcherServlet'
2026-11-04 14:28:36.069 INFO  [http-nio-8080-exec-4] o.s.web.servlet.DispatcherServlet - Initializing Servlet 'dispatcherServlet'
2026-11-04 14:28:36.071 INFO  [http-nio-8080-exec-4] o.s.web.servlet.DispatcherServlet - Completed initialization in 1 ms
2026-11-04 14:28:40.558 INFO  [slotmind-workflow-1] c.slotmind.strategy.LiteLLMGateway - AI gateway request started | trigger=PROVIDER_OFFLINE | model=qwen-cursor | candidateCount=3
2026-11-04 14:28:42.051 INFO  [slotmind-workflow-1] c.slotmind.strategy.LiteLLMGateway - AI gateway response received | trigger=PROVIDER_OFFLINE | durationMs=1496 | responsePresent=true
2026-11-04 14:28:42.051 INFO  [slotmind-workflow-1] c.slotmind.strategy.LiteLLMGateway - AI response parsing | trigger=PROVIDER_OFFLINE | validRecommendation=true
2026-11-04 14:28:42.067 INFO  [slotmind-workflow-1] c.s.strategy.AISchedulingStrategy - Scheduling recommendation selected | trigger=PROVIDER_OFFLINE | source=AI | candidateCount=3 | alternatives=2
2026-11-04 14:28:42.092 INFO  [slotmind-workflow-1] c.slotmind.service.SchedulingService - Scheduling suggestion persisted | trigger=PROVIDER_OFFLINE | source=AI | suggestionId=cb9235f9-7473-43ef-96ce-cea17654e38b | candidateCount=3
2026-11-04 14:28:42.106 INFO  [slotmind-workflow-1] c.slotmind.strategy.LiteLLMGateway - AI gateway request started | trigger=PROVIDER_OFFLINE | model=qwen-cursor | candidateCount=2
2026-11-04 14:28:42.657 INFO  [slotmind-workflow-1] c.slotmind.strategy.LiteLLMGateway - AI gateway response received | trigger=PROVIDER_OFFLINE | durationMs=554 | responsePresent=true
2026-11-04 14:28:42.657 INFO  [slotmind-workflow-1] c.slotmind.strategy.LiteLLMGateway - AI response parsing | trigger=PROVIDER_OFFLINE | validRecommendation=true
2026-11-04 14:28:42.673 INFO  [slotmind-workflow-1] c.s.strategy.AISchedulingStrategy - Scheduling recommendation selected | trigger=PROVIDER_OFFLINE | source=AI | candidateCount=2 | alternatives=1
2026-11-04 14:28:42.674 INFO  [slotmind-workflow-1] c.slotmind.service.SchedulingService - Scheduling suggestion persisted | trigger=PROVIDER_OFFLINE | source=AI | suggestionId=7be7a0e2-9d4b-4085-b0b6-01ef6ff60bb1 | candidateCount=2