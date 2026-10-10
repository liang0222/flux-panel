package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

// Panel 单个面板端的连接配置
type Panel struct {
	Addr   string `json:"addr"`
	Secret string `json:"secret"`
}

// Config 配置结构体
//
// 同时兼容两种写法：
//
//	1) 单面板（旧格式，官方脚本生成，保持兼容）：
//	   { "addr": "1.2.3.4:6365", "secret": "xxx" }
//
//	2) 多面板（新格式，一个节点同时连接多个面板）：
//	   {
//	     "http": 0, "tls": 0, "socks": 0,
//	     "panels": [
//	       { "addr": "1.2.3.4:6365", "secret": "xxx" },
//	       { "addr": "5.6.7.8:6365", "secret": "yyy" }
//	     ]
//	   }
type Config struct {
	Addr   string `json:"addr"`
	Secret string `json:"secret"`
	Http   int    `json:"http"`
	Tls    int    `json:"tls"`
	Socks  int    `json:"socks"`

	// Panels 多面板列表。为空时回退到上面的 Addr/Secret 单面板写法。
	Panels []Panel `json:"panels"`
}

// LoadConfig 加载配置文件
func LoadConfig(configPath string) (*Config, error) {
	// 检查文件是否存在
	if _, err := os.Stat(configPath); os.IsNotExist(err) {
		return nil, fmt.Errorf("配置文件不存在: %s", configPath)
	}

	// 读取文件内容
	data, err := os.ReadFile(configPath)
	if err != nil {
		return nil, fmt.Errorf("读取配置文件失败: %v", err)
	}

	// 去掉 UTF-8 BOM。
	// 用户在 Windows 上用记事本等编辑器修改 config.json 时很容易带上 BOM，
	// 而 Go 的 JSON 解析器不接受 BOM，会报 "invalid character 'ï'"，
	// 这个报错很难让人联想到 BOM，所以这里直接清理掉。
	data = bytes.TrimPrefix(data, []byte{0xEF, 0xBB, 0xBF})

	// 解析JSON
	var config Config
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, fmt.Errorf("解析配置文件失败: %v", err)
	}

	// 归一化：把单面板写法合并进 Panels，后续代码统一按多面板处理
	config.normalize()

	// 验证必要的配置项
	if len(config.Panels) == 0 {
		return nil, fmt.Errorf("服务器地址不能为空")
	}
	for i, p := range config.Panels {
		if strings.TrimSpace(p.Addr) == "" {
			return nil, fmt.Errorf("第 %d 个面板的 addr 不能为空", i+1)
		}
	}

	if err := config.validate(); err != nil {
		return nil, err
	}

	return &config, nil
}

// normalize 把旧的单面板字段合并进 Panels，并去重。
func (c *Config) normalize() {
	// 旧格式：顶层 addr + secret
	if strings.TrimSpace(c.Addr) != "" {
		c.Panels = append(c.Panels, Panel{Addr: c.Addr, Secret: c.Secret})
	}

	seen := make(map[string]bool)
	deduped := make([]Panel, 0, len(c.Panels))
	for _, p := range c.Panels {
		p.Addr = strings.TrimSpace(p.Addr)
		p.Secret = strings.TrimSpace(p.Secret)
		if p.Addr == "" {
			continue
		}
		// 同一 addr+secret 只保留一条，避免配置重复导致重复连接
		k := p.Addr + "\x00" + p.Secret
		if seen[k] {
			continue
		}
		seen[k] = true
		deduped = append(deduped, p)
	}
	c.Panels = deduped

	// 清掉旧字段，避免后续代码误用（统一只从 Panels 取）
	c.Addr = ""
	c.Secret = ""
}

// validate 校验多个面板之间是否存在明显冲突。
func (c *Config) validate() error {
	// 同一 addr 出现多次通常意味着重复配置；若 secret 不同则是明显的配置错误
	byAddr := make(map[string]string)
	for _, p := range c.Panels {
		if prev, ok := byAddr[p.Addr]; ok && prev != p.Secret {
			return fmt.Errorf(
				"面板 %s 配置了不同的 secret：同一面板地址只应配置一次，"+
					"如需连接两个不同面板，请确保它们的地址不同",
				p.Addr)
		}
		byAddr[p.Addr] = p.Secret
	}
	return nil
}

// isolatePrefix 返回该面板用于隔离服务名的前缀。
//
// 为什么需要：节点上的服务名由面板生成，格式为 forwardId_userId_userTunnelId，
// 而两个面板的 forwardId 各自都是从 1 开始自增，必然出现同名服务。
// 若不隔离，A 面板下发的「删除服务」会把 B 面板的同名服务一起删掉。
//
// 前缀取自 addr，只保留字符与数字，保证生成的服务名是合法且可读的。
func (p Panel) isolatePrefix() string {
	var b strings.Builder
	b.WriteString("p_")
	for _, r := range p.Addr {
		switch {
		case r >= '0' && r <= '9', r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z':
			b.WriteRune(r)
		case r == '.' || r == ':' || r == '-':
			b.WriteRune('_')
		}
	}
	return b.String()
}
