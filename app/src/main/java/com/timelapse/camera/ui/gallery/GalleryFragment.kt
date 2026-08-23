package com.timelapse.camera.ui.gallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.timelapse.camera.databinding.FragmentGalleryBinding
import com.timelapse.camera.databinding.ItemPhotoBinding
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.storage.IPhotoStorage
import com.timelapse.camera.storage.PhotoStorageFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 相册页 Fragment —— 网格浏览历史照片。
 *
 * 第一版功能：
 * - Grid 布局（3 列）展示所有照片
 * - Coil 加载缩略图，自动缓存和采样
 * - 按时间倒序（最新的在最前面）
 *
 * 后续可扩展：点击大图、分享、删除、延时连播等。
 *
 * 教学要点：
 * - RecyclerView + Adapter 的标准写法
 * - Coil 图片加载的简洁用法
 * - 后台线程读文件，主线程更新 UI
 */
class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private lateinit var storage: IPhotoStorage
    private val adapter = PhotoAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = PhotoStorageFactory.create(requireContext(), CaptureConfig.load(requireContext()))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = GridLayoutManager(context, 3)
        binding.recyclerView.adapter = adapter

        // 首次进入即加载，避免用户切换 Tab 时才看到空屏
        loadPhotos()
    }

    override fun onResume() {
        super.onResume()
        // 重建 storage 以感知存储位置变更（onCreate 已保证首次初始化）
        // GalleryFragment 不持有 config 字段，单次 load + create 已天然一致
        storage = PhotoStorageFactory.create(requireContext(), CaptureConfig.load(requireContext()))
        loadPhotos()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadPhotos() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val photos = storage.getAllPhotos()
            withContext(Dispatchers.Main) {
                adapter.setPhotos(photos)
                binding.tvEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ──────────── Adapter ────────────

    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoViewHolder>() {

        private val photos = mutableListOf<File>()

        fun setPhotos(newPhotos: List<File>) {
            photos.clear()
            photos.addAll(newPhotos)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val binding = ItemPhotoBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return PhotoViewHolder(binding)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            holder.bind(photos[position])
        }

        override fun getItemCount(): Int = photos.size
    }

    private inner class PhotoViewHolder(
        private val binding: ItemPhotoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(photo: File) {
            binding.ivPhoto.load(photo) {
                placeholder(android.R.color.darker_gray)
                crossfade(true)
            }
        }
    }

    companion object {
        fun newInstance() = GalleryFragment()
    }
}
