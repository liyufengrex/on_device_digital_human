/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppMotionSyncLinkView.hpp"
#include <cmath>
#include <string>
#include "LAppPal.hpp"
#include "LAppMotionSyncLinkDelegate.hpp"
#include "LAppDefine.hpp"
#include "LAppMotionSyncLinkModel.hpp"
#include "LAppMotionSyncDefine.hpp"

#include <Rendering/OpenGL/CubismRenderer_OpenGLES2.hpp>

#include "JniBridgeC.hpp"

using namespace std;
using namespace LAppDefine;
using namespace LAppMotionSyncDefine;
using namespace Csm;

namespace {
    int CompareCsmString(const void* a, const void* b)
    {
        return strcmp(reinterpret_cast<const Csm::csmString*>(a)->GetRawString(),
                      reinterpret_cast<const Csm::csmString*>(b)->GetRawString());
    }
}

LAppMotionSyncLinkView::LAppMotionSyncLinkView():
        _userModel(NULL),
        _modelIndex(0),
        _changeModel(false),
        _changeSound(false)
{
    // 表示するモデルの一覧を作成
    LoadModelName();
}

LAppMotionSyncLinkView::~LAppMotionSyncLinkView()
{
    if (_userModel)
    {
        _userModel->DeleteRenderer();
    }
    delete _userModel;
}

void LAppMotionSyncLinkView::Initialize()
{
    LoadModel();
}

void LAppMotionSyncLinkView::Render()
{
    // 音声の切り替え
    if (_changeSound)
    {
        _changeSound = false;
        if (_userModel)
        {
            _userModel->ChangeNextMotion();
        }
    }

    // モデルの切り替え
    if (_changeModel)
    {
        _changeModel = false;
        ChangeNextModel();
    }

    // モデルの描画
    if (_userModel)
    {
        _userModel->Update();
    }
}

void LAppMotionSyncLinkView::RequestPlayNext()
{
    _changeSound = true;
}

void LAppMotionSyncLinkView::RequestSwitchModel()
{
    _changeModel = true;
}

void LAppMotionSyncLinkView::OnTouchesBegan(float pointX, float pointY) const
{
}

void LAppMotionSyncLinkView::OnTouchesMoved(float pointX, float pointY) const
{
}

void LAppMotionSyncLinkView::OnTouchesEnded(float pointX, float pointY)
{
}

void LAppMotionSyncLinkView::LoadModelName()
{
    const char MODEL3_JSON[] = u8".model3.json";
    const char* modelRoot = "live_2d_model";
    _modelNameList.Clear();
    Csm::csmVector<Csm::csmString> root = JniBridgeC::GetAssetList(modelRoot);
    for (size_t i = 0; i < root.GetSize(); i++)
    {
        Csm::csmString target(root[i]);
        target.Append(MODEL3_JSON, sizeof(MODEL3_JSON) - 1);
        Csm::csmString dir = Csm::csmString(modelRoot) + "/" + root[i];
        Csm::csmVector<Csm::csmString> sub = JniBridgeC::GetAssetList(dir.GetRawString());
        for (size_t j = 0; j < sub.GetSize(); j++)
        {
            if (target == sub[j])
            {
                _modelNameList.PushBack(root[i]);
                break;
            }
        }
    }
    qsort(_modelNameList.GetPtr(), _modelNameList.GetSize(), sizeof(csmString), CompareCsmString);
}

void LAppMotionSyncLinkView::LoadModel()
{
    if (_userModel)
    {
        // レンダラの解放
        _userModel->DeleteRenderer();

        delete _userModel;
    }

    // モデルデータの新規生成
    _userModel = new LAppMotionSyncLinkModel();

    if (_modelIndex < _modelNameList.GetSize())
    {
        // モデルデータの読み込み及び生成とセットアップを行う
        _userModel->LoadAssets(_modelNameList[_modelIndex]);
        if (_userModel->GetModel() != NULL)
        {
            JniBridgeC::NotifyModelLoaded();
        }
    }
}

void LAppMotionSyncLinkView::ChangeNextModel()
{
    if (0 < _modelNameList.GetSize())
    {
        _modelIndex = (_modelIndex + 1) % _modelNameList.GetSize();
        LoadModel();
    }
}

LAppMotionSyncLinkModel* LAppMotionSyncLinkView::GetCurrentModel() const
{
    return _userModel;
}
