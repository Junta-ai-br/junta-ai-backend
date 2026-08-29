const express = require('express');
const cors = require('cors');

const app = express();

app.use(cors());
app.use(express.json());

// Rota de relatórios
app.get('/api/reports', (req, res) => {
    return res.status(200).json({
        sucesso: true,
        dados: [
            { id: 1, relatorio: 'Mensagens Enviadas', valor: 320 },
            { id: 2, relatorio: 'Contatos Ativos', valor: 85 },
            { id: 3, relatorio: 'Campanhas Concluídas', valor: 12 }
        ]
    });
});

// Rota de integração/webhook
app.post('/api/webhook', (req, res) => {
    console.log('Dados recebidos:', req.body);
    return res.status(200).json({ status: 'Sucesso', mensagem: 'Integração concluída' });
});

// listen na porta 3000
app.listen(3000, () => {
    console.log('Servidor rodando na porta 3000');
});