/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppMotionSyncLinkModel.hpp"
#include "../../main/cpp/JniBridgeC.hpp"
#include "CubismModelMotionSyncSettingJson.hpp"
#include "LAppDefine.hpp"
#include "LAppMotionSyncLinkDelegate.hpp"
#include "LAppMotionSyncDefine.hpp"
#include "LAppPal.hpp"
#include "LAppTextureManager.hpp"
#include "Rendering/OpenGL/CubismRenderer_OpenGLES2.hpp"
#include "LAppMotionEventDefine.hpp"
#include "Motion/CubismPhysicsUpdater.hpp"
#include "CubismMotionSyncUpdater.hpp"
#include "Motion/CubismPoseUpdater.hpp"
#include "Utils/CubismString.hpp"
#include "CubismFramework.hpp"
#include "Id/CubismIdManager.hpp"

#include <cmath>
#include <GLES2/gl2.h>

using namespace Csm;
using namespace MotionSync;
using namespace LAppDefine;
using namespace LAppMotionSyncDefine;
using namespace LAppCubismMotionEventDefine;

namespace {
    struct IdleSpec
    {
        const csmChar* id;
        csmFloat32 speedHz;
        csmFloat32 rangeRatio;
        csmFloat32 phase;
        csmBool aroundDefault;
    };

    // 振幅是参数活动范围的一小部分，频率错开，避免整齐地来回摆。
    const IdleSpec IdleSpecs[] = {
        {"ParamAngleX", 0.18f, 0.12f, 0.0f, true},
        {"ParamAngleY", 0.13f, 0.08f, 1.3f, true},
        {"ParamAngleZ", 0.11f, 0.06f, 2.1f, true},
        {"ParamBodyAngleX", 0.15f, 0.18f, 0.6f, true},
        {"ParamBodyAngleY", 0.10f, 0.12f, 1.8f, true},
        {"ParamBodyAngleZ", 0.08f, 0.10f, 2.6f, true},
        {"ParamBreath", 0.22f, 0.35f, 0.4f, false},
    };

    // 正值朝参数最大值。Z 让头侧倾，X 让脸转向同一侧，身体跟着偏一点。
    struct ListenSpec
    {
        const csmChar* id;
        csmFloat32 signedRatio;
    };

    const ListenSpec ListenSpecs[] = {
        {"ParamAngleX", 0.35f},
        {"ParamAngleY", -0.18f},
        {"ParamAngleZ", 0.62f},
        {"ParamBodyAngleX", 0.22f},
        {"ParamBodyAngleZ", 0.35f},
    };

    const csmFloat32 ListenBlendSeconds = 0.7f;
    const csmFloat32 ListenReleaseSeconds = 1.05f;

    csmByte* CreateBuffer(const csmChar* path, csmSizeInt* size)
    {
        if (LAppDefine::DebugLogEnable)
        {
            LAppPal::PrintLogLn("[APP]create buffer: %s ", path);
        }
        return LAppPal::LoadFileAsBytes(path, size);
    }

    void DeleteBuffer(csmByte* buffer, const csmChar* path = "")
    {
        if (DebugLogEnable)
        {
            LAppPal::PrintLogLn("[APP]delete buffer: %s", path);
        }
        LAppPal::ReleaseBytes(buffer);
    }

    // 只溶衣服下摆。比例是模型画布高度，0.46 会盖到脖子。
    const csmFloat32 BodyFadePortion = 0.16f;

    GLuint BodyFadeProgram()
    {
        static GLuint program = 0;
        if (program != 0 && glIsProgram(program))
        {
            return program;
        }

        const GLchar* vertex =
            "attribute vec2 aPos;\n"
            "void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }\n";
        const GLchar* fragment =
            "precision mediump float;\n"
            "uniform float uHeight;\n"
            "uniform float uFadeStart;\n"
            "uniform float uFadeEnd;\n"
            "void main(){\n"
            "  float y = (gl_FragCoord.y / uHeight) * 2.0 - 1.0;\n"
            "  float t = clamp((y - uFadeEnd) / (uFadeStart - uFadeEnd), 0.0, 1.0);\n"
            "  float a = 1.0 - t;\n"
            "  a = a * a * (3.0 - 2.0 * a);\n"
            "  gl_FragColor = vec4(0.0, 0.0, 0.0, a);\n"
            "}\n";

        GLuint vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, 1, &vertex, NULL);
        glCompileShader(vs);
        GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, 1, &fragment, NULL);
        glCompileShader(fs);
        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glBindAttribLocation(program, 0, "aPos");
        glLinkProgram(program);
        glDeleteShader(vs);
        glDeleteShader(fs);

        GLint linked = 0;
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
        if (!linked)
        {
            LAppPal::PrintLogLn("[APP]body fade shader link failed");
            glDeleteProgram(program);
            program = 0;
            return 0;
        }
        return program;
    }

    void DrawBodyFade(csmFloat32 windowHeight, csmFloat32 modelBottom, csmFloat32 modelTop)
    {
        const GLuint program = BodyFadeProgram();
        if (program == 0 || windowHeight <= 0.0f)
        {
            return;
        }

        const csmFloat32 fadeStart = modelBottom + (modelTop - modelBottom) * BodyFadePortion;
        const csmFloat32 fadeEnd = modelBottom;
        const GLfloat vertices[] = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
        };

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, LAppMotionSyncLinkDelegate::GetInstance()->GetWindowWidth(), static_cast<int>(windowHeight));
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(program);
        glUniform1f(glGetUniformLocation(program, "uHeight"), windowHeight);
        glUniform1f(glGetUniformLocation(program, "uFadeStart"), fadeStart);
        glUniform1f(glGetUniformLocation(program, "uFadeEnd"), fadeEnd);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, vertices);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(0);
        glUseProgram(0);
    }
}

LAppMotionSyncLinkModel::LAppMotionSyncLinkModel()
    : CubismUserModel()
    , _modelSetting(NULL)
    , _userTimeSeconds(0)
    , _motionSync(NULL)
    , _motionIndex(-1)
    , _isMotionSync(NULL)
    , _currentMotionSyncSettingIndex(0)
    , _isEnableMotionSync(false)
    , _idleEnabled(false)
    , _listenEnabled(false)
    , _listenWeight(0.0f)
    , _listenTarget(0.0f)
    , _currentMotionSyncMotion(NULL)
{
}

LAppMotionSyncLinkModel::~LAppMotionSyncLinkModel()
{
    ReleaseMotions();

    if (_motionSync)
    {
        _soundData.Release();
        CubismMotionSync::Delete(_motionSync);
    }

    delete _modelSetting;
}

void LAppMotionSyncLinkModel::LoadAssets(const csmString fileName)
{
    _modelHomeDir = csmString(ResourcesPath) + fileName + "/";

    if (_debugMode)
    {
        LAppPal::PrintLogLn("[APP]load model setting: %s", fileName.GetRawString());
    }

    csmSizeInt size;
    const csmString path = _modelHomeDir + fileName + ".model3.json";

    csmByte* buffer = CreateBuffer(path.GetRawString(), &size);
    _modelSetting = new CubismModelMotionSyncSettingJson(buffer, size);
    DeleteBuffer(buffer, path.GetRawString());

    SetupModel();

    if (_model == NULL)
    {
        LAppPal::PrintLogLn("Failed to LoadAssets().");
        return;
    }

    CreateRenderer(LAppMotionSyncLinkDelegate::GetInstance()->GetWindowWidth(), LAppMotionSyncLinkDelegate::GetInstance()->GetWindowHeight());

    // テクスチャのセットアップ
    SetupTextures();
}

void LAppMotionSyncLinkModel::Update()
{
    const csmFloat32 deltaTimeSeconds = LAppPal::GetDeltaTime();
    _userTimeSeconds += deltaTimeSeconds;

    // モーションによるパラメータ更新の有無
    csmBool motionUpdated = false;

    //------------------------------------------------------
    _model->LoadParameters(); // 前回セーブされた状態をロード
    motionUpdated = _motionManager->UpdateMotion(_model, deltaTimeSeconds); // モーションを更新
    _model->SaveParameters(); // 状態を保存
    //------------------------------------------------------

    // 音声データを更新
    if (_isMotionSync && _isEnableMotionSync)
    {
      _soundData.Update();
    }

    // 不透明度
    _opacity = _model->GetModelOpacity();

    // 写在物理演算之前，头发才能跟着头和身体的角度动。不改眼睛和嘴。
    if (_idleEnabled)
    {
        ApplyIdlePose();
    }
    // 写在晃动之后，头和身体停在附耳的角度。眼睛、嘴和呼吸不动。
    if (_listenEnabled)
    {
        ApplyListenPose();
    }

    _updateScheduler.OnLateUpdate(_model, deltaTimeSeconds);

    // cdi3 里的 Part8「SDK表示」是右下角的 Vowels 标记。Update 会把部件不透明度乘进网格。
    for (csmInt32 partIndex = 0; partIndex < _model->GetPartCount(); ++partIndex)
    {
        if (strcmp(_model->GetPartId(partIndex)->GetString().GetRawString(), "Part8") == 0)
        {
            _model->SetPartOpacity(partIndex, 0.0f);
            break;
        }
    }

    _model->Update();

    // ウィンドウサイズを取得
    int width = LAppMotionSyncLinkDelegate::GetInstance()->GetWindowWidth();
    int height = LAppMotionSyncLinkDelegate::GetInstance()->GetWindowHeight();
    float displayRatio = static_cast<float>(height) / static_cast<float>(width);
    float aspectRatio = static_cast<float>(width) / static_cast<float>(height);
    float canvasRatio = _model->GetCanvasHeight() / _model->GetCanvasWidth();

    Csm::CubismMatrix44 projection;
    // 念のため単位行列に初期化
    projection.LoadIdentity();

    if (canvasRatio < displayRatio)
    {
        // 横長モデルを幅に合わせて縦方向のスケールを調整
        GetModelMatrix()->SetWidth(2.0f);
        projection.Scale(1.0f, aspectRatio);
    }
    else
    {
        // 縦長モデルを高さに合わせて横方向のスケールを調整
        GetModelMatrix()->SetHeight(2.0f);
        projection.Scale(1.0f / aspectRatio, 1.0f);
    }

    // モデルの描画を更新
    Draw(projection); ///< 参照渡しなのでprojectionは変質する

    // 宽模型按宽度适配后，画布在屏幕里居中，衣服底边高于屏幕底边。羽化跟画布走。
    const float modelHalf = (canvasRatio < displayRatio) ? canvasRatio * aspectRatio : 1.0f;
    DrawBodyFade(static_cast<float>(height), -modelHalf, modelHalf);
}

Csm::csmBool LAppMotionSyncLinkModel::PlayAudio(const csmByte* bytes, csmSizeInt size)
{
    if (!_motionSync || !_isMotionSync || bytes == NULL || size < 4)
    {
        return false;
    }

    if (_currentMotionSyncSettingIndex >= 0)
    {
        _motionSync->SetSoundBuffer(_currentMotionSyncSettingIndex, NULL);
    }
    _soundData.Release();
    _isEnableMotionSync = false;
    // 口形はモーションの終了に紐づけない。目のモーションはそのまま残す。
    _currentMotionSyncMotion = NULL;

    _currentMotionSyncSettingIndex = 0;
    if (!_soundData.LoadWav(bytes, size, 0))
    {
        _currentMotionSyncSettingIndex = -1;
        return false;
    }

    _isEnableMotionSync = true;
    _motionSync->SetSoundBuffer(_currentMotionSyncSettingIndex, _soundData.GetBuffer());
    LAppPal::PrintLogLn("[APP] Play audio bytes: %d", size);
    return true;
}

Csm::csmBool LAppMotionSyncLinkModel::PlayIdle()
{
    if (_model == NULL)
    {
        return false;
    }

    _idleEnabled = true;
    LAppPal::PrintLogLn("[APP] Play idle sway");
    return true;
}

void LAppMotionSyncLinkModel::ApplyIdlePose()
{
    const csmFloat32 time = _userTimeSeconds;
    for (csmUint32 i = 0; i < sizeof(IdleSpecs) / sizeof(IdleSpecs[0]); ++i)
    {
        const IdleSpec& spec = IdleSpecs[i];
        const csmInt32 index = _model->GetParameterIndex(CubismFramework::GetIdManager()->GetId(spec.id));
        if (index < 0)
        {
            continue;
        }

        const csmFloat32 minValue = _model->GetParameterMinimumValue(index);
        const csmFloat32 maxValue = _model->GetParameterMaximumValue(index);
        const csmFloat32 defaultValue = _model->GetParameterDefaultValue(index);
        const csmFloat32 wave = sinf(time * spec.speedHz * 6.2831853f + spec.phase);
        csmFloat32 value = defaultValue;
        if (spec.aroundDefault)
        {
            value = defaultValue + wave * (maxValue - minValue) * 0.5f * spec.rangeRatio;
        }
        else
        {
            value = defaultValue + (wave * 0.5f + 0.5f) * (maxValue - minValue) * spec.rangeRatio;
        }
        _model->SetParameterValue(index, value);
    }
}

Csm::csmBool LAppMotionSyncLinkModel::PlayListen()
{
    if (_model == NULL)
    {
        return false;
    }

    _listenEnabled = true;
    _listenTarget = 1.0f;
    LAppPal::PrintLogLn("[APP] Play listen pose");
    return true;
}

Csm::csmBool LAppMotionSyncLinkModel::ReleaseListen()
{
    if (_model == NULL || (!_listenEnabled && _listenWeight <= 0.0f))
    {
        return false;
    }

    _listenEnabled = true;
    _listenTarget = 0.0f;
    LAppPal::PrintLogLn("[APP] Release listen pose");
    return true;
}

void LAppMotionSyncLinkModel::ApplyListenPose()
{
    const csmFloat32 duration = (_listenTarget > _listenWeight) ? ListenBlendSeconds : ListenReleaseSeconds;
    csmFloat32 step = 0.0f;
    if (duration > 0.0f)
    {
        step = LAppPal::GetDeltaTime() / duration;
    }
    if (_listenWeight < _listenTarget)
    {
        _listenWeight += step;
        if (_listenWeight > _listenTarget)
        {
            _listenWeight = _listenTarget;
        }
    }
    else if (_listenWeight > _listenTarget)
    {
        _listenWeight -= step;
        if (_listenWeight < _listenTarget)
        {
            _listenWeight = _listenTarget;
        }
    }

    if (_listenTarget <= 0.0f && _listenWeight <= 0.0f)
    {
        _listenEnabled = false;
        _listenWeight = 0.0f;
        return;
    }

    csmFloat32 blend = _listenWeight;
    blend = blend * blend * (3.0f - 2.0f * blend);

    for (csmUint32 i = 0; i < sizeof(ListenSpecs) / sizeof(ListenSpecs[0]); ++i)
    {
        const ListenSpec& spec = ListenSpecs[i];
        const csmInt32 index = _model->GetParameterIndex(CubismFramework::GetIdManager()->GetId(spec.id));
        if (index < 0)
        {
            continue;
        }

        const csmFloat32 minValue = _model->GetParameterMinimumValue(index);
        const csmFloat32 maxValue = _model->GetParameterMaximumValue(index);
        const csmFloat32 defaultValue = _model->GetParameterDefaultValue(index);
        const csmFloat32 halfRange = (maxValue - minValue) * 0.5f;
        const csmFloat32 target = defaultValue + spec.signedRatio * halfRange;
        _model->SetParameterValue(index, defaultValue + (target - defaultValue) * blend);
    }
}

Csm::csmBool LAppMotionSyncLinkModel::PlayEyes()
{
    if (_modelSetting == NULL || _motionManager == NULL)
    {
        return false;
    }

    if (_modelSetting->GetMotionCount("") < 1)
    {
        return false;
    }

    csmString name = Utils::CubismString::GetFormatedString("%s_%d", "", 0);
    CubismMotion* motion = static_cast<CubismMotion*>(_motions[name.GetRawString()]);
    if (motion == NULL)
    {
        return false;
    }

    // 目のモーションが終わっても音声を止めない。
    motion->SetFinishedMotionHandler(NULL);
    // motion3.json 的 Loop 不会自动生效，这里打开后 14.4 秒的眨眼会反复播放。
    motion->SetLoop(true);
    motion->SetLoopFadeIn(false);
    _motionManager->SetReservePriority(PriorityForce);
    _motionManager->StartMotionPriority(motion, false, PriorityForce);
    LAppPal::PrintLogLn("[APP] Play eyes motion");
    return true;
}

void LAppMotionSyncLinkModel::ChangeNextMotion()
{
    _motionIndex += 1;
    if (_motionIndex < 0 || _motionIndex >= _motions.GetSize())
    {
        _motionIndex = 0;
    }

    StartMotion("", _motionIndex, LAppDefine::PriorityForce, LAppCubismMotionEventDefine::OnFinishedMotion, NULL);
}

Csm::CubismMotionQueueEntryHandle LAppMotionSyncLinkModel::StartMotion(const Csm::csmChar* group, Csm::csmInt32 no,
    Csm::csmInt32 priority, Csm::ACubismMotion::FinishedMotionCallback onFinishedMotionHandler,
    Csm::ACubismMotion::BeganMotionCallback onBeganMotionHandler)
{
    if (_motionSync == NULL)
    {
        LAppPal::PrintLogLn("[APP]MotionSync is not initialized.");
        return InvalidMotionQueueEntryHandleValue;
    }

    const csmString fileName = _modelSetting->GetMotionFileName(group, no);
    if (!_motionSyncSettingMap.IsExist(fileName))
    {
        LAppPal::PrintLogLn("[APP]MotionSync setting not found: [%s_%d] %s", group, no, fileName.GetRawString());
        return InvalidMotionQueueEntryHandleValue;
    }

    if (!_soundFileMap.IsExist(fileName))
    {
        LAppPal::PrintLogLn("[APP]Sound file not found: [%s_%d] %s", group, no, fileName.GetRawString());
        return InvalidMotionQueueEntryHandleValue;
    }

    if (priority == LAppDefine::PriorityForce)
    {
        _motionManager->SetReservePriority(priority);
    }
    else if (!_motionManager->ReserveMotion(priority))
    {
        if (_debugMode)
        {
            LAppPal::PrintLogLn("[APP]can't start motion.");
        }
        return InvalidMotionQueueEntryHandleValue;
    }

    //ex) idle_0
    csmString name = Utils::CubismString::GetFormatedString("%s_%d", group, no);
    CubismMotion* motion = static_cast<CubismMotion*>(_motions[name.GetRawString()]);
    csmBool autoDelete = false;

    if (motion == NULL)
    {
        csmString path = fileName;
        path = _modelHomeDir + path;

        csmByte* buffer;
        csmSizeInt size;
        buffer = CreateBuffer(path.GetRawString(), &size);
        motion = static_cast<CubismMotion*>(LoadMotion(buffer, size, NULL, onFinishedMotionHandler, onBeganMotionHandler));

        if (motion)
        {
            csmFloat32 fadeTime = _modelSetting->GetMotionFadeInTimeValue(group, no);
            if (fadeTime >= 0.0f)
            {
                motion->SetFadeInTime(fadeTime);
            }

            fadeTime = _modelSetting->GetMotionFadeOutTimeValue(group, no);
            if (fadeTime >= 0.0f)
            {
                motion->SetFadeOutTime(fadeTime);
            }
            autoDelete = true; // 終了時にメモリから削除
        }

        DeleteBuffer(buffer, path.GetRawString());
    }
    else
    {
        motion->SetBeganMotionHandler(onBeganMotionHandler);
        motion->SetFinishedMotionHandler(onFinishedMotionHandler);
    }

    // MotionSync
    csmString useMotionSyncSettingName = _motionSyncSettingMap[fileName];
    csmString useSoundFileName = _soundFileMap[fileName];

    csmInt32 motionSyncSettingIndex = -1;
    for (csmInt32 i = 0; i < _motionSync->GetCubismMotionSyncData()->GetSettingListSize(); i++)
    {
        csmString settingIdName = _motionSync->GetCubismMotionSyncData()->GetMeta().dictionary.At(i).name;

        if (settingIdName == useMotionSyncSettingName)
        {
            motionSyncSettingIndex = i;
            break;
        }
    }

    if (motionSyncSettingIndex != -1)
    {
        _currentSoundFileName = useSoundFileName;
        _currentMotionSyncSettingIndex = motionSyncSettingIndex;
        // このモーションが現在のモーションシンクの所有者
        _currentMotionSyncMotion = motion;

        // 音声ファイルのロードと再生の開始
        StartMotionSync();
    }
    else
    {
        if (_debugMode)
        {
            LAppPal::PrintLogLn("[APP]Failed set motionSync: [%s_%d]", group, no);
        }

        _currentSoundFileName = "";
        _currentMotionSyncSettingIndex = -1;
    }

    if (_debugMode)
    {
        LAppPal::PrintLogLn("[APP]start motion: [%s_%d]", group, no);
    }
    return  _motionManager->StartMotionPriority(motion, autoDelete, priority);
}

void LAppMotionSyncLinkModel::OnFinishedMotion(Csm::ACubismMotion* self)
{
    // 早送りなどでモーションを切り替えた場合、旧モーションの終了コールバックが
    // 新しく開始したモーションシンクを止めてしまうのを防ぐ。
    // 現在のモーションシンクを再生しているモーションが終了した場合のみ破棄する。
    if (self != _currentMotionSyncMotion)
    {
        return;
    }

    // 音声再生の終了と音声バッファのクリア
    if (_motionSync && _currentMotionSyncSettingIndex >= 0)
    {
        _motionSync->SetSoundBuffer(_currentMotionSyncSettingIndex, NULL);
    }
    _soundData.Release();

    // モーションシンクの無効化
    _isEnableMotionSync = false;
    _currentMotionSyncMotion = NULL;


    if (_debugMode)
    {
        LAppPal::PrintLogLn("[APP] Finish motion sync: %s%s", _modelHomeDir.GetRawString(), _currentSoundFileName.GetRawString());
    }
}

void LAppMotionSyncLinkModel::StartMotionSync()
{
    if (_currentMotionSyncSettingIndex < 0 || _currentSoundFileName.GetLength() < 1)
    {
        if (_debugMode)
        {
            LAppPal::PrintLogLn("[APP]Failed begin motion sync.\n_currentMotionSyncSettingIndex: %d, _currentSoundFileName length: %d",
                _currentMotionSyncSettingIndex, _currentSoundFileName.GetLength());
        }
        return;
    }

    // モーションシンクの有効化
    _isEnableMotionSync = true;

    // 音声再生の開始
    if (_debugMode)
    {
        LAppPal::PrintLogLn("[APP] Begin motion sync: %s%s", _modelHomeDir.GetRawString(), _currentSoundFileName.GetRawString());
    }
    // Paths under audio_demo/ are asset-root paths. Other sound paths stay next to the model.
    const csmChar* audioDemoPrefix = "audio_demo/";
    const csmChar* soundRaw = _currentSoundFileName.GetRawString();
    csmBool fromAudioDemo = true;
    for (csmInt32 i = 0; audioDemoPrefix[i] != '\0'; ++i)
    {
        if (soundRaw[i] != audioDemoPrefix[i])
        {
            fromAudioDemo = false;
            break;
        }
    }
    csmString soundPath = fromAudioDemo ? _currentSoundFileName : _modelHomeDir + _currentSoundFileName;
    _soundData.LoadFile(soundPath, 0);
    _motionSync->SetSoundBuffer(_currentMotionSyncSettingIndex, _soundData.GetBuffer());
}


void LAppMotionSyncLinkModel::SetupModel()
{
    _updating = true;
    _initialized = false;

    csmByte* buffer;
    csmSizeInt size;

    //Cubism Model
    if (strcmp(_modelSetting->GetModelFileName(), "") != 0)
    {
        csmString path = _modelSetting->GetModelFileName();
        path = _modelHomeDir + path;

        if (_debugMode)
        {
            LAppPal::PrintLogLn("[APP]create model: %s", _modelSetting->GetModelFileName());
        }

        buffer = CreateBuffer(path.GetRawString(), &size);
        LoadModel(buffer, size, _mocConsistency);
        DeleteBuffer(buffer, path.GetRawString());
    }

    if (_modelSetting == NULL || _modelMatrix == NULL)
    {
        LAppPal::PrintLogLn("Failed to SetupModel().");
        return;
    }

    // Physics
    if (strcmp(_modelSetting->GetPhysicsFileName(), "") != 0)
    {
        csmString path = _modelSetting->GetPhysicsFileName();
        path = _modelHomeDir + path;

        buffer = CreateBuffer(path.GetRawString(), &size);
        LoadPhysics(buffer, size);
        DeleteBuffer(buffer, path.GetRawString());

        if (_physics != nullptr)
        {
            _updateScheduler.AddUpdatableList(CSM_NEW CubismPhysicsUpdater(*_physics));
        }
    }

    // Pose
    if (strcmp(_modelSetting->GetPoseFileName(), "") != 0)
    {
        csmString path = _modelSetting->GetPoseFileName();
        path = _modelHomeDir + path;

        buffer = CreateBuffer(path.GetRawString(), &size);
        LoadPose(buffer, size);
        DeleteBuffer(buffer, path.GetRawString());

        if (_pose != nullptr)
        {
            _updateScheduler.AddUpdatableList(CSM_NEW CubismPoseUpdater(*_pose));
        }
    }

    // Layout
    csmMap<csmString, csmFloat32> layout;
    _modelSetting->GetLayoutMap(layout);
    _modelMatrix->SetupFromLayout(layout);

    // パラメータを保存
    _model->SaveParameters();

    // Motion
    for (csmInt32 i = 0; i < _modelSetting->GetMotionGroupCount(); i++)
    {
        const csmChar* group = _modelSetting->GetMotionGroupName(i);
        PreloadMotionGroup(group);
    }

    _motionManager->StopAllMotions();

    // MotionSync
    const csmChar* fileName = _modelSetting->GetMotionSyncJsonFileName();

    if (strcmp(fileName, "") != 0)
    {
        if (_debugMode)
        {
            LAppPal::PrintLogLn("[APP]load motionSync setting: %s", fileName);
        }

        const csmString path = csmString(_modelHomeDir) + fileName;
        buffer = CreateBuffer(path.GetRawString(), &size);

        _motionSync = CubismMotionSync::Create(_model, buffer, size, SamplesPerSec, JniBridgeC::GetJniLibPath() + "/");

        if (_motionSync == nullptr)
        {
            LAppPal::PrintLogLn("Failed to SetupModel().");
            return;
        }

        DeleteBuffer(buffer, path.GetRawString());

        _updateScheduler.AddUpdatableList(CSM_NEW CubismMotionSyncUpdater(*_motionSync));

        // 音声データ
        // モーション名とそれに紐づくデータを保存
        _modelSetting->GetMotionSyncLinkSettingMap(_motionSyncSettingMap);
        _modelSetting->GetMotionSyncLinkAudioMap(_soundFileMap);
        _motionIndex = -1;
        _isMotionSync = true;
    }

    _updateScheduler.SortUpdatableList();

    _updating = false;
    _initialized = true;
}

void LAppMotionSyncLinkModel::SetupTextures()
{
    for (csmInt32 modelTextureNumber = 0; modelTextureNumber < _modelSetting->GetTextureCount(); modelTextureNumber++)
    {
        // テクスチャ名が空文字だった場合はロード・バインド処理をスキップ
        if (!strcmp(_modelSetting->GetTextureFileName(modelTextureNumber), ""))
        {
            continue;
        }

        // OpenGLのテクスチャユニットにテクスチャをロードする
        csmString texturePath = _modelSetting->GetTextureFileName(modelTextureNumber);
        texturePath = _modelHomeDir + texturePath;

        LAppTextureManager::TextureInfo* texture = LAppMotionSyncLinkDelegate::GetInstance()->GetTextureManager()->CreateTextureFromPngFile(texturePath.GetRawString());
        const csmInt32 glTextueNumber = texture->id;

        // OpenGL
        GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->BindTexture(modelTextureNumber, glTextueNumber);
    }

    // 乗算済みアルファ値の有効化・無効化を設定
    GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->IsPremultipliedAlpha(false);
}


void LAppMotionSyncLinkModel::PreloadMotionGroup(const Csm::csmChar* group)
{
    const csmInt32 count = _modelSetting->GetMotionCount(group);

    for (csmInt32 i = 0; i < count; i++)
    {
        //ex) idle_0
        csmString name = Utils::CubismString::GetFormatedString("%s_%d", group, i);
        csmString path = _modelSetting->GetMotionFileName(group, i);
        path = _modelHomeDir + path;

        if (_debugMode)
        {
            LAppPal::PrintLogLn("[APP]load motion: %s => [%s_%d] ", path.GetRawString(), group, i);
        }

        csmByte* buffer;
        csmSizeInt size;
        buffer = CreateBuffer(path.GetRawString(), &size);
        CubismMotion* tmpMotion = static_cast<CubismMotion*>(LoadMotion(buffer, size, name.GetRawString()));

        if (tmpMotion)
        {
            csmFloat32 fadeTime = _modelSetting->GetMotionFadeInTimeValue(group, i);
            if (fadeTime >= 0.0f)
            {
                tmpMotion->SetFadeInTime(fadeTime);
            }

            fadeTime = _modelSetting->GetMotionFadeOutTimeValue(group, i);
            if (fadeTime >= 0.0f)
            {
                tmpMotion->SetFadeOutTime(fadeTime);
            }

            if (_motions[name] != NULL)
            {
                ACubismMotion::Delete(_motions[name]);
            }
            _motions[name] = tmpMotion;
        }

        DeleteBuffer(buffer, path.GetRawString());
    }
}

void LAppMotionSyncLinkModel::ReleaseMotions()
{
    for (csmMap<csmString, ACubismMotion*>::const_iterator iter = _motions.Begin(); iter != _motions.End(); ++iter)
    {
        ACubismMotion::Delete(iter->Second);
    }

    _motions.Clear();
}


void LAppMotionSyncLinkModel::Draw(Csm::CubismMatrix44& matrix)
{
    if (!_model)
    {
        return;
    }

    // 現在の行列に行列を乗算
    matrix.MultiplyByMatrix(_modelMatrix);

    // 行列をモデルビュープロジェクション行列を設定
    GetRenderer<Csm::Rendering::CubismRenderer_OpenGLES2>()->SetMvpMatrix(&matrix);

    // モデルの描画を命令・実行する
    GetRenderer<Csm::Rendering::CubismRenderer_OpenGLES2>()->DrawModel();
}
