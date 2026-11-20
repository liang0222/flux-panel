import { useState, useEffect } from 'react';
import { Input } from "@heroui/input";
import { Button } from "@heroui/button";
import { Card, CardBody } from "@heroui/card";
import { useNavigate } from 'react-router-dom';
import toast from 'react-hot-toast';
import { applyPanelAddresses } from '@/api/network';
import {
  getPanelAddresses, savePanelAddress, setCurrentPanelAddress, deletePanelAddress, normalizePanelAddress,
} from '@/utils/panel';
import type { PanelAddress } from '@/utils/panel';

export const SettingsPage = () => {
  const navigate = useNavigate();
  const [panelAddresses, setPanelAddresses] = useState<PanelAddress[]>([]);
  const [newName, setNewName] = useState('');
  const [newAddress, setNewAddress] = useState('');
  const [busy, setBusy] = useState(true);

  const updateAddresses = async (operation: () => Promise<PanelAddress[]>, success?: string) => {
    setBusy(true);
    try {
      const addresses = await operation();
      applyPanelAddresses(addresses);
      setPanelAddresses(addresses);
      if (success) toast.success(success);
      return true;
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '面板设置服务暂不可用');
      return false;
    } finally {
      setBusy(false);
    }
  };

  const addPanelAddress = async () => {
    const name = newName.trim();
    const address = normalizePanelAddress(newAddress);
    if (!name || !address) {
      toast.error('请输入名称及完整 HTTPS 地址，例如 https://panel.example.com');
      return;
    }
    if (await updateAddresses(() => savePanelAddress(name, address), '添加成功')) {
      setNewName('');
      setNewAddress('');
    }
  };

  useEffect(() => {
    void updateAddresses(getPanelAddresses);
  }, []);

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-black">
      {/* 顶部导航 */}
      <div className="bg-white dark:bg-black border-b border-gray-200 dark:border-gray-700">
        <div className="max-w-4xl mx-auto px-4 py-4">
          <div className="flex items-center gap-3">
            <Button
              isIconOnly
              variant="light"
              isDisabled={busy}
              onClick={() => navigate('/', { replace: true })}
              className="text-gray-600 dark:text-gray-300"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
              </svg>
            </Button>
            <h1 className="text-xl font-semibold text-gray-900 dark:text-white">面板设置</h1>
          </div>
        </div>
      </div>

      {/* 内容区域 */}
      <div className="max-w-4xl mx-auto px-4 py-6">
        <div className="space-y-6">
          {/* 添加新地址 */}
          <Card className="border border-gray-200 dark:border-gray-700">
            <CardBody className="p-6">
              <h2 className="text-lg font-medium text-gray-900 dark:text-white mb-4">添加新面板地址</h2>
              <div className="space-y-4">
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <Input
                    label="名称"
                    placeholder="请输入面板名称"
                    maxLength={100}
                    value={newName}
                    onChange={(e) => setNewName(e.target.value)}
                  />
                  <Input
                    label="地址"
                    placeholder="https://panel.example.com"
                    maxLength={2048}
                    value={newAddress}
                    onChange={(e) => setNewAddress(e.target.value)}
                  />
                </div>
                <Button color="primary" isDisabled={busy} onClick={addPanelAddress}>
                  添加
                </Button>
              </div>
            </CardBody>
          </Card>

          {/* 地址列表 */}
          <Card className="border border-gray-200 dark:border-gray-700">
            <CardBody className="p-6">
              <h2 className="text-lg font-medium text-gray-900 dark:text-white mb-4">已保存的面板地址</h2>
              {panelAddresses.length === 0 ? (
                <p className="text-gray-500 dark:text-gray-400 text-center py-8">暂无保存的面板地址</p>
              ) : (
                <div className="space-y-3">
                  {panelAddresses.map((panel, index) => (
                    <div key={index} className="border border-gray-200 dark:border-gray-600 rounded-lg p-4">
                      <div className="flex items-center justify-between">
                        <div className="flex-1">
                          <div className="flex items-center gap-2">
                            <span className="font-medium text-gray-900 dark:text-white">{panel.name}</span>
                            {panel.inx && normalizePanelAddress(panel.address) && (
                              <span className="px-2 py-1 bg-green-100 dark:bg-green-500/20 text-green-700 dark:text-green-300 text-xs rounded">
                                当前
                              </span>
                            )}
                          </div>
                          <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">{normalizePanelAddress(panel.address) ?? '旧地址不可用，请添加 HTTPS 地址并重新选择'}</p>
                        </div>
                        <div className="flex items-center gap-2">
                          {!panel.inx && normalizePanelAddress(panel.address) && (
                            <Button
                              size="sm"
                              color="primary"
                              variant="flat"
                              isDisabled={busy}
                              onClick={() => updateAddresses(() => setCurrentPanelAddress(panel.name))}
                            >
                              设为当前
                            </Button>
                          )}
                          <Button
                            size="sm"
                            color="danger"
                            variant="light"
                            isDisabled={busy}
                            onClick={() => updateAddresses(() => deletePanelAddress(panel.name), '删除成功')}
                          >
                            删除
                          </Button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardBody>
          </Card>
        </div>
      </div>
    </div>
  );
};
