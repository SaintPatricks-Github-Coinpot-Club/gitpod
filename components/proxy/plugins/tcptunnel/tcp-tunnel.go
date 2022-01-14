// Copyright (c) 2022 Gitpod GmbH. All rights reserved.
// Licensed under the GNU Affero General Public License (AGPL).
// See License-AGPL.txt in the project root for license information.

package tcptunnel

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"

	"github.com/caddyserver/caddy/v2"
)

func init() {
	caddy.RegisterModule(SSHTunnel{})
}

type SSHTunnel struct {
	listener net.Listener
}

func (SSHTunnel) CaddyModule() caddy.ModuleInfo {
	return caddy.ModuleInfo{
		ID:  "ssh-tunnel",
		New: func() caddy.Module { return new(SSHTunnel) },
	}
}

func (s *SSHTunnel) Provision(ctx caddy.Context) error {
	return nil
}

func (s *SSHTunnel) Start() error {
	ln, err := caddy.Listen("tcp", ":2200")
	if err != nil {
		return err
	}
	s.listener = ln
	go s.serve()
	fmt.Println("ssh tunnel is running")
	return nil
}

func (s SSHTunnel) Stop() error {
	err := s.listener.Close()
	if err != nil {
		return err
	}
	return nil
}

func (s *SSHTunnel) serve() {
	for {
		conn, err := s.listener.Accept()
		if nerr, ok := err.(net.Error); ok && nerr.Temporary() {
			// ignore temporary network error
			continue
		}
		if err != nil {
			return
		}
		go s.handle(conn)
	}
}

func (s *SSHTunnel) handle(conn net.Conn) {
	defer conn.Close()
	addr := fmt.Sprintf("ws-proxy.%s.%s:2200", os.Getenv("KUBE_NAMESPACE"), os.Getenv("KUBE_DOMAIN"))
	tconn, err := net.Dial("tcp", addr)
	if err != nil {
		fmt.Printf("dial %s failed with:%v\n", addr, err)
		return
	}
	defer tconn.Close()
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		io.Copy(conn, tconn)
		cancel()
	}()

	go func() {
		io.Copy(tconn, conn)
		cancel()
	}()
	<-ctx.Done()
}

var _ caddy.App = (*SSHTunnel)(nil)
