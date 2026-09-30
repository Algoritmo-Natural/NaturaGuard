# NaturaGuard (Android) — Nuno Camara | Algoritmo Natural

App Android de vigilância de segurança local. Pacote `com.algoritmonatural.naturaguard`, versão 0.2.0.

## Estado honesto

- **Compila** e os testes unitários da lógica de comparação passam (8/8), construído com Gradle 8.7, AGP 8.5.2, JDK 17, SDK 34.
- **Ainda não foi testado num telemóvel real.** Antes de confiar nele, instale o APK de debug e experimente cada função.
- **Sem permissão `INTERNET`:** o núcleo não comunica com o exterior.

## O que faz

| Função | Onde | Como |
| --- | --- | --- |
| Login | `MainActivity.kt` | Só abre com biometria ou PIN/padrão do próprio telemóvel (`BiometricPrompt`). Volta a bloquear ao sair. Bloqueia capturas de ecrã. Tentativas falhadas de abrir a app geram alerta. |
| Tentativas de desbloqueio | `admin/GuardAdminReceiver.kt` | Administrador do dispositivo só com `watch-login`: avisa de cada PIN errado no ecrã de bloqueio (3 em 10 min = crítico). Não pode apagar nem bloquear nada. O Android marca este mecanismo como descontinuado; pode não funcionar em todas as versões/marcas. |
| Vigilância periódica | `scan/` | A cada 15 min (WorkManager) compara com a referência: serviços de acessibilidade novos, apps administradoras novas, leitores de notificações novos, apps instaladas fora da loja, ADB/opções de programador, root, sem bloqueio de ecrã, patch de segurança com mais de 120 dias. |
| Alertas | `shared/` | `events.jsonl` só de acrescentar; marcar como visto grava em `marks.txt` sem reescrever nada. Notificação para aviso e crítico. |
| Root | `rootdetection/` | Heurística; é um sinal, não uma prova. |
| Uso de apps | `usageaudit/` | Só depois de o utilizador conceder o acesso nas Definições. |

## Limites

- A referência inicial assume que o telemóvel estava limpo na primeira verificação.
- Não lê o conteúdo de notificações nem usa `AccessibilityService` para vigiar.
- Root escondido pode enganar a heurística.
- `QUERY_ALL_PACKAGES` é necessária para listar apps instaladas (a Play Store restringe-a; esta app não se destina à loja).

## Removido nesta versão

- Monitor VPN (reenviava pacotes para o túnel e cortaria a internet).
- Modo Device Owner (exige aprovisionamento de fábrica e não era usado).

## Como compilar

```
set JAVA_HOME=<JDK 17>
set ANDROID_HOME=<SDK com platforms;android-34 e build-tools;34.0.0>
gradle :app:testDebugUnitTest :app:assembleDebug
```

O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

## Próximo passo

Integrar o repositório WireGuard (túnel VPN com configuração cifrada pelo Keystore).
