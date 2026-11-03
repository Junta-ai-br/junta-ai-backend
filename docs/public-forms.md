# Formulários públicos: Contato e Feedback

`POST /contact` e `POST /feedback` aceitam JSON sem autenticação:

```json
{
  "name": "Ana",
  "email": "ana@example.com",
  "subject": "outro",
  "subjectOther": "Meu assunto",
  "message": "Primeira linha.\nSegunda linha."
}
```

Todos os valores usados são normalizados com `strip()` antes da validação
de obrigatoriedade e tamanho. `name`, `email`, `subject` e `message` são
obrigatórios. Limites em unidades UTF-16 (Java e JavaScript): nome 100, e-mail 254,
código de assunto 32, assunto personalizado 120, mensagem 3000.
O e-mail precisa ter formato válido; isso não comprova a titularidade.
Caracteres de controle e separadores de linha são rejeitados nos campos de
cabeçalho, inclusive antes da normalização. As quebras internas da mensagem
são preservadas. `subjectOther` é obrigatório somente para `outro`; nos
demais casos seu conteúdo é ignorado. Campos extras não definem remetente
ou destinatário e são ignorados pelo desserializador padrão.

| Formulário | Código | Rótulo do frontend |
| --- | --- | --- |
| Contato | faq | Não encontrei minha dúvida na FAQ |
| Contato | junta | Dúvida sobre o Junta.ai |
| Contato | sugestao | Sugestão ou ideia |
| Contato | parceria | Parceria ou colaboração |
| Contato | imprensa | Imprensa |
| Contato | outro | Texto de subjectOther |
| Feedback | experiencia | Experiência com o Junta.ai |
| Feedback | sugestao | Sugestão ou nova ideia |
| Feedback | problema | Encontrei um problema |
| Feedback | duvida | Algo não ficou claro |
| Feedback | privacidade | Privacidade e segurança |
| Feedback | financeiro | Recursos financeiros |
| Feedback | assistente | Assistente / IA |
| Feedback | outro | Texto de subjectOther |

Assuntos dos e-mails: `[Contato Junta.ai] <rótulo>` ou
`[Feedback Junta.ai] <rótulo>`. O texto simples UTF-8 contém origem, nome,
e-mail, assunto e mensagem. O destinatário vem exclusivamente de
`app.mail.contact-to`; remetente de `MAIL_FROM`; `Reply-To` do e-mail validado.
Não há persistência de mensagens, tabelas ou migrations novas.

Após `JavaMailSender.send` concluir, a API retorna **200 `{"status":"sent"}`**:
aceitação pelo SMTP, sem confirmação de entrega na caixa postal.
Validação/JSON inválido: **400**; corpo HTTP excessivo: **413**; cota excedida: **429**;
configuração ausente/inválida, capacidade ocupada ou falha de envio: **503**.
Erros tratados pelo handler global incluem `timestamp`, `status` numérico,
`erro`, `mensagem` e, quando aplicável, `campos`.
Há dois caminhos existentes para HTTP 413: a rejeição antecipada pelo filtro,
quando `Content-Length` excede o teto, responde diretamente sem `timestamp`:

```json
{"status":413,"erro":"Payload Too Large","mensagem":"Requisição grande demais."}
```

Quando o excesso é detectado durante a leitura, a exceção é tratada pelo
handler global e a resposta inclui também `timestamp`. Ambos os caminhos
retornam 413 com os mesmos `status`, `erro` e `mensagem`; o frontend usa o
status HTTP e não depende da presença de `timestamp`.
Não retornamos nem registramos o conteúdo ou as exceções SMTP.
O modo console local/dev continua disponível para OTP, mas formulários
retornam 503 quando não há SMTP real configurado. A política atual do OTP
continua impedindo a inicialização com SMTP incompleto fora de local/dev.
Ausência de `CONTACT_EMAIL_TO` não impede o OTP; indisponibiliza formulários.

## Configuração na Azure

Configure as variáveis de aplicação (o Spring não lê `.env` automaticamente):

| Variável | Valor/uso |
| --- | --- |
| MAIL_HOST | smtp.gmail.com (ou host SMTP já usado pelo OTP) |
| MAIL_PORT | 587 |
| MAIL_USERNAME | Conta SMTP autorizada |
| MAIL_PASSWORD | Segredo SMTP da conta, como a senha de app já usada pelo OTP |
| MAIL_FROM | Conta ou alias autorizado pelo SMTP |
| CONTACT_EMAIL_TO | contato.junta.ai@gmail.com |
| CORS_ALLOWED_ORIGINS | https://junta-ai.vercel.app |
| PUBLIC_FORMS_RATE_LIMIT_PER_CLIENT | 5 por janela, padrão |
| PUBLIC_FORMS_RATE_LIMIT_GLOBAL | 100 por janela, padrão |
| PUBLIC_FORMS_RATE_LIMIT_WINDOW_SECONDS | 600, máximo 86400 |
| PUBLIC_FORMS_RATE_LIMIT_MAX_CLIENTS | 10000 entradas ativas, padrão |
| PUBLIC_FORMS_MAX_CONCURRENT_SENDS | 2 envios simultâneos de contato/feedback por instância, padrão; inteiro positivo |

Mantenha os demais ajustes existentes de banco, JWT e login. Não use
wildcard no CORS. As origens adicionais podem ser separadas por vírgula.
Garanta saída para o host/porta SMTP; STARTTLS obrigatório e timeouts
existentes são reutilizados. Não ative debug de SMTP nem logs de payloads.

## Proteção contra abuso e proxies

A cota é compartilhada entre os dois endpoints, conta tentativas inclusive
inválidas e falhas SMTP e usa janelas fixas, sincronizadas em memória.
Há limite por `request.getRemoteAddr()`, limite global e limite de entradas
ativas. A capacidade cheia retorna 429 sem expulsar cotas ativas.
Preflight OPTIONS não consome cota e passa pelo CORS existente; os demais
métodos e rotas privadas continuam exigindo autenticação.

O código atual usa `server.forward-headers-strategy=native`, preservado neste
hardening. O Tomcat pode reconstruir `getRemoteAddr()` usando `X-Forwarded-For`
quando a conexão vem de um proxy considerado confiável. O teste com Tomcat
local comprova que, recebendo `203.0.113.9, 198.51.100.10` de loopback confiável,
a cota recebe `198.51.100.10` (endereço não confiável mais à direita).
O teste MockMvc de headers falsificados, sozinho, não valida esse comportamento.

Segundo a [documentação do Azure Container Apps](https://learn.microsoft.com/azure/container-apps/ingress-overview#http-headers),
o ingress acrescenta valores ao `X-Forwarded-For`; apenas o IP mais à direita
é fornecido pelo Container Apps. Isso não comprova a configuração implantada.
Permanecem pendentes: identificar proxies intermediários e seus intervalos
confiáveis, confirmar overrides de runtime, restrições de acesso ao backend,
tratamento dos headers e quantidade de réplicas. Validar em staging com poucos
pedidos controlados, observando temporariamente apenas o IP efetivo, sem payload,
sem segredos e sem retenção adicional de dados pessoais. Não testar falsificação
contra produção e não mudar a estratégia ou os proxies sem aprovação específica.

Esta proteção não é distribuída: cada réplica tem sua própria cota e
reinícios apagam contadores; duas instâncias podem permitir duas cotas.
Janelas fixas permitem rajadas próximas à virada da janela. Para maior
escala, use limite compartilhado ou proteção no gateway. CORS não impede
chamadas de scripts e não substitui o limite de requisições.

## Corpo HTTP e concorrência SMTP

Somente `POST /contact` e `POST /feedback` recebem um wrapper de leitura
limitada, com o caminho da aplicação decodificado para acompanhar o roteamento
(por exemplo, `/%63ontact`). O teto é 65536 bytes (64 KiB), ou um `app.max-request-bytes` menor
caso já configurado. O filtro mantém a checagem global preexistente de
`Content-Length`; os outros endpoints não recebem o novo wrapper.

O corpo é lido sob demanda, depois do interceptor de rate limiting e antes
da desserialização: no máximo teto + 1 byte, sem buffering ilimitado. O byte
adicional detecta excesso mesmo sem `Content-Length` (chunked), incluindo
campos extras e conteúdo após o objeto JSON. O excesso retorna 413 sem envio.
`max-swallow-size` não substitui esse controle de leitura.

Contato e feedback compartilham um semáforo local à instância, sem fila nem
espera por permissão. O padrão de 2 preserva um orçamento pequeno diante das
100 threads Tomcat configuradas por padrão; não é uma capacidade medida do
Azure. Ajustar somente após verificar recursos e cotas SMTP. A permissão cobre
composição/envio e é liberada em `finally`, inclusive após erro. Capacidade
ocupada retorna o mesmo 503 genérico de indisponibilidade. OTP/login não usam
esse semáforo e os timeouts SMTP existentes de 5 segundos foram mantidos.

Não há `Retry-After` novo: 503 não tem prazo previsível para liberação de
capacidade, e 429 preserva a orientação de aguardar a janela existente, sem
publicar uma estimativa que ignore as cotas por cliente, global e capacidade.
O frontend mantém o conteúdo, trata 413 explicitamente e não tenta reenviar
automaticamente. O contador da mensagem usa UTF-16, sem truncar colagens;
emojis podem ocupar duas unidades. Erros e contador ficam associados ao campo.

## Testes automatizados

O repositório não possuía wrapper; foi incluído Maven Wrapper oficial
`only-script`, Maven 3.9.16. Use JDK 21. Em PowerShell:

```powershell
.\mvnw.cmd '-Dtest=PublicFormControllerTest,PublicFormRateLimiterTest,RequestSizeLimitFilterTest,PublicFormMailServiceTest,PublicFormTransportTest,EmailAccessCodeServiceTest,AuthServiceTest,GmailEmailSenderTest,EmailSenderConfigTest' test
```

Os testes usam JavaMailSender mockado, sem SMTP real. Cobrem payloads,
rótulos, cabeçalhos, corpo UTF-8, erros seguros, console/configuração,
cotas, concorrência, CORS e a cadeia real de segurança com JWT mockado.
Os testes existentes do OTP e serviços são incluídos nesse comando.
Para a suíte completa, disponibilize o PostgreSQL de teste conforme README
e execute `.\mvnw.cmd test`. Não aponte para banco de produção.

Validação histórica da implementação inicial: **118 testes, zero falhas/erros/skips**, via
`mvnw.cmd -B test` com JDK 21 e PostgreSQL 17 temporário em
`127.0.0.1:55432`, separado do banco da aplicação e encerrado ao final.
Inclui a regressão de autenticação/OTP existente. Nenhum e-mail real foi
enviado. `git diff --check` também passou.

O hardening acrescenta limites UTF-16, composição literal de HTML como texto,
contenção/liberação do SMTP, leitura limitada antes do SMTP e teste HTTP/1.1 com
Tomcat local em porta aleatória (65535, 65536 e 65537 bytes, com Content-Length
e chunked). Todos os envios são mockados; não exige PostgreSQL nem Azure.
Os resultados desta rodada são registrados na entrega, separados da evidência histórica.

### Evidências do hardening (03/11/2026)

- Backend: comando direcionado acima, **121 testes aprovados**, sem falhas,
  erros ou skips; inclui regressão unitária de OTP/autenticação. `git diff --check` aprovado.
- Frontend: `npm run test:run -- src/services/public-forms.test.js src/pages/PublicForms.test.jsx src/app/routes.test.jsx`,
  **120 testes aprovados**; suíte completa `npm run test:run -- --maxWorkers=2`,
  **1091 testes / 65 arquivos aprovados**; ESLint, build e diff check aprovados.
  A primeira execução sem limitar workers teve uma falha de espera no teste
  não alterado `VisaoMes.test.jsx`; passou isoladamente e na suíte completa com
  os mesmos dois workers do CI. Nenhum timeout ou teste fora do escopo foi alterado.
- A suíte integral do backend com PostgreSQL não foi executada: o Docker local
  está indisponível. Os testes novos de transporte usam Tomcat real, sem banco.
- Nenhum envio SMTP, pedido ao Azure ou teste contra produção foi realizado.

Resumo para documentação interna futura (nenhum documento interno criado):

| Documento | Evidência / pendência |
| --- | --- |
| sec-04-api-security.md | Limites UTF-16, teto real de 64 KiB, 413/429/503 e semáforo público |
| sec-06-infrastructure.md | Variável de concorrência e controles locais; validar proxy, overrides e réplicas no Azure |
| sec-07-threat-model.md | Contenção de payload e SMTP; risco residual de uploads lentos e abuso entre réplicas |
| sec-08-validation.md | Comandos/resultados acima, testes mockados e transporte local; entrega SMTP e infraestrutura não verificados |

## Arquivos desta alteração

- Implementação: `PublicFormController.java`, `PublicFormRequest.java`,
  `PublicFormMailService.java`, `FormOrigin.java`, `PublicFormRateLimiter.java`,
  `PublicFormWebConfig.java`, `SecurityConfig.java`, `GlobalExceptionHandler.java`,
  `RequestSizeLimitFilter.java`.
- Configuração e documentação: `src/main/resources/application.yml`,
  `.env.example`, `README.md`, `docs/public-forms.md`.
- Testes: `PublicFormControllerTest.java`, `PublicFormMailServiceTest.java`,
  `PublicFormRateLimiterTest.java`, `RequestSizeLimitFilterTest.java`,
  `PublicFormTransportTest.java`, `EmailSenderConfigTest.java`,
  `AbstractIntegrationTest.java` (SMTP mockado e health check SMTP desativado
  somente nos testes).
- Wrapper oficial: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`.

`EmailSender`, `GmailEmailSender`, `ConsoleEmailSender` e a implementação
de geração/validação do OTP permanecem sem alterações.

## Roteiro operacional controlado (staging, com autorização)

Não executar estes passos automaticamente em produção. Envios para destinatários
reais exigem autorização específica. Validar limites e falhas preferencialmente
com mocks locais; inspeção do Azure depende de acesso e aprovação operacional.

1. Configure as variáveis acima e publique a versão revisada.
2. Faça OPTIONS nos dois endpoints com `Origin: https://junta-ai.vercel.app`,
   `Access-Control-Request-Method: POST` e
   `Access-Control-Request-Headers: content-type`. Espere 200 e
   `Access-Control-Allow-Origin` com a origem exata. Origem diferente: 403.
3. Envie um POST válido a cada endpoint, sem token, com texto de teste
   sem dados pessoais sensíveis e assunto identificável. Espere 200/sent.
4. Confirme manualmente os dois e-mails no Gmail, inclusive spam:
   destinatário fixo, remetente autorizado, prefixo, rótulo, acentos e
   quebras de linha. Ao responder, confirme o Reply-To da pessoa.
5. Teste `outro` com/sem subjectOther e códigos exclusivos do outro
   formulário: inválidos devem retornar 400. GET /contact e uma rota
   privada sem token devem retornar 401. Verifique também login por OTP.
6. Em uma janela controlada, ultrapasse a cota com payloads inválidos
   para não gerar e-mails: espere 429; aguarde a janela e repita.
7. Em staging, remova CONTACT_EMAIL_TO ou simule falha SMTP: espere 503
   genérico, nunca sent. Restaure e reteste antes de promover alterações.

O frontend implementa POSTs e tratamento de 400/413/429/503 no repositório
`junta-ai`, em branch correspondente `fix/public-forms-mvp-hardening`.
Testes automatizados não confirmam entrega real
no Gmail: só um teste manual confirmado permite essa afirmação.

## Resumo para PR pública

Implementa POST /contact e POST /feedback anônimos com validação, assuntos
do frontend e envio SMTP existente. Usa destinatário fixo no servidor,
MAIL_FROM autorizado e Reply-To validado; retorna sent após aceitação SMTP
e erros seguros para indisponibilidade. Adiciona cotas configuráveis em
memória, testes de segurança/CORS/SMTP mockado e documentação de implantação,
preservando o fluxo OTP e sem alterações no banco.
