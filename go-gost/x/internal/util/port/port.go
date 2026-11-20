package port

import (
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strconv"
	"sync"
	"time"

	"github.com/go-gost/core/logger"
)

func ForceClosePortConnections(serviceName, addr string) error {
	_, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("parse service address: %w", err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("invalid port %q", portStr)
	}

	log := logger.Default().WithFields(map[string]any{"service": serviceName, "port": port})
	return startAndReap(exec.Command("tcpkill", "-i", "any", "port", strconv.Itoa(port)), 2*time.Second, log)
}

func startAndReap(cmd *exec.Cmd, duration time.Duration, log logger.Logger) error {
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start tcpkill: %w", err)
	}

	go func() {
		var timeout sync.WaitGroup
		timeout.Add(1)
		killed := false
		timer := time.AfterFunc(duration, func() {
			defer timeout.Done()
			if err := cmd.Process.Kill(); err == nil {
				killed = true
			} else if !errors.Is(err, os.ErrProcessDone) {
				log.Warnf("stop tcpkill: %v", err)
			}
		})
		err := cmd.Wait()
		if timer.Stop() {
			timeout.Done()
		}
		timeout.Wait()
		if err != nil && !killed {
			log.Warnf("wait tcpkill: %v", err)
		}
	}()
	return nil
}
