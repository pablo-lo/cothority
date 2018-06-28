package protocol

import (
	omniledger "github.com/dedis/cothority/omniledger/service"
	"github.com/dedis/onet"
)

type CollectTxProtocol struct {
	*onet.TreeNodeInstance
}

type CollectTxRequest struct {
}

type CollectTxResponse struct {
	Txs omniledger.ClientTransactions
}

func (p *CollectTxProtocol) Start() error {
	return nil
}

func (p *CollectTxProtocol) Dispatch() error {
	return nil
}
