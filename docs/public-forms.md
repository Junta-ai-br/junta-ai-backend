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
obrigatórios. Limites em unidades UTF-16 (Java): nome 120, e-mail 254,
código de assunto 32, assunto personalizado 160, mensagem 10000.
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
Validação/JSON inválido: **400**; limite excedido: **429**;
configuração ausente/inválida ou falha de envio: **503**.
Erros seguem o contrato existente: `timestamp`, `status` numérico,
`erro`, `mensagem` e, quando aplicável, `campos`.
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

`server.forward-headers-strategy=none` evita aceitar arbitrariamente
`X-Forwarded-For`/`Forwarded`. Atrás do proxy da Azure, clientes podem
compartilhar o endereço do proxy e portanto a mesma cota. Não habilite
confiança indiscriminada nos headers para contornar isso. Para identificar
clientes na borda, configure proxy confiável com sanitização e limite no
gateway, após verificar a topologia real.

Esta proteção não é distribuída: cada réplica tem sua própria cota e
reinícios apagam contadores; duas instâncias podem permitir duas cotas.
Janelas fixas permitem rajadas próximas à virada da janela. Para maior
escala, use limite compartilhado ou proteção no gateway. CORS não impede
chamadas de scripts e não substitui o limite de requisições.

## Testes automatizados

O repositório não possuía wrapper; foi incluído Maven Wrapper oficial
`only-script`, Maven 3.9.16. Use JDK 21. Em PowerShell:

```powershell
.\mvnw.cmd '-Dtest=*ServiceTest,*EmailSenderTest,EmailSenderConfigTest,PublicFormControllerTest,PublicFormRateLimiterTest' test
```

Os testes usam JavaMailSender mockado, sem SMTP real. Cobrem payloads,
rótulos, cabeçalhos, corpo UTF-8, erros seguros, console/configuração,
cotas, concorrência, CORS e a cadeia real de segurança com JWT mockado.
Os testes existentes do OTP e serviços são incluídos nesse comando.
Para a suíte completa, disponibilize o PostgreSQL de teste conforme README
e execute `.\mvnw.cmd test`. Não aponte para banco de produção.

Validação desta implementação: **118 testes, zero falhas/erros/skips**, via
`mvnw.cmd -B test` com JDK 21 e PostgreSQL 17 temporário em
`127.0.0.1:55432`, separado do banco da aplicação e encerrado ao final.
Inclui a regressão de autenticação/OTP existente. Nenhum e-mail real foi
enviado. `git diff --check` também passou.

## Arquivos desta alteração

- Implementação: `PublicFormController.java`, `PublicFormRequest.java`,
  `PublicFormMailService.java`, `FormOrigin.java`, `PublicFormRateLimiter.java`,
  `PublicFormWebConfig.java`, `SecurityConfig.java`, `GlobalExceptionHandler.java`.
- Configuração e documentação: `src/main/resources/application.yml`,
  `.env.example`, `README.md`, `docs/public-forms.md`.
- Testes: `PublicFormControllerTest.java`, `PublicFormMailServiceTest.java`,
  `PublicFormRateLimiterTest.java`, `EmailSenderConfigTest.java`,
  `AbstractIntegrationTest.java` (SMTP mockado e health check SMTP desativado
  somente nos testes).
- Wrapper oficial: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`.

`EmailSender`, `GmailEmailSender`, `ConsoleEmailSender` e a implementação
de geração/validação do OTP permanecem sem alterações.

## Roteiro manual em produção

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

O frontend ainda precisa fazer os POSTs e tratar 400/429/503; esta alteração
é no repositório backend. Testes automatizados não confirmam entrega real
no Gmail: só um teste manual confirmado permite essa afirmação.

## Resumo para PR pública

Implementa POST /contact e POST /feedback anônimos com validação, assuntos
do frontend e envio SMTP existente. Usa destinatário fixo no servidor,
MAIL_FROM autorizado e Reply-To validado; retorna sent após aceitação SMTP
e erros seguros para indisponibilidade. Adiciona cotas configuráveis em
memória, testes de segurança/CORS/SMTP mockado e documentação de implantação,
preservando o fluxo OTP e sem alterações no banco.
