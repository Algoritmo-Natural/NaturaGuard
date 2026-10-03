# NaturaGuard

**Algoritmo Natural — Sustentabilidade Digital**

NaturaGuard é um projeto de segurança móvel da Algoritmo Natural, uma agência digital e empresa tecnológica sediada nos Açores. Este repositório contém uma app Android que explora ferramentas de monitorização e auditoria de segurança com consentimento do utilizador.

## Estado do projeto

| Estado | Descrição |
| --- | --- |
| **Em desenvolvimento** | Código-fonte inicial da app Android (`naturaguard-android/`). |
| **Não validado** | Ainda não foi compilado nem testado num dispositivo ou emulador. |
| **Planeado** | Validação no Android Studio, testes em dispositivo e revisão de permissões. |

Não é um produto concluído nem está disponível na Play Store.

## Funcionalidades (em desenvolvimento)

- **Monitor de rede**: `VpnService` local, sem root, que regista ligações a portas de gestão suspeitas sem bloquear tráfego.
- **Auditoria de uso**: leitura das estatísticas de utilização das últimas 24 horas, apenas com permissão concedida manualmente.
- **Modo dispositivo dedicado**: receiver de Device Owner para aprovisionamento.
- **Deteção de root**: heurística de sinais conhecidos, um indício e não uma garantia.
- **Registo de eventos**: `events.jsonl` no armazenamento privado da app.

## Princípios

- Privacidade e consentimento do utilizador em primeiro lugar.
- Sem leitura de notificações de outras apps nem uso de acessibilidade para vigilância.
- Sem segredos, credenciais ou dados pessoais neste repositório.

## Como compilar

1. Instalar o [Android Studio](https://developer.android.com/studio).
2. Abrir a pasta `naturaguard-android/` como projeto.
3. Sincronizar o Gradle (SDK 34).
4. **Build → Make Project** e corrigir eventuais erros.

Mais detalhes em [`naturaguard-android/README.md`](naturaguard-android/README.md).

## Licença

Distribuído sob a licença [Apache-2.0](LICENSE).

© Algoritmo Natural
